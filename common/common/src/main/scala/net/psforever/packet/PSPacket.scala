// Copyright (c) 2016 PSForever.net to present
package net.psforever.packet

import java.nio.charset.Charset

import scodec.{DecodeResult, Err, Codec, Attempt}
import scodec.bits._
import scodec.codecs._
import scodec._
import shapeless._

/** The base of all packets */
sealed trait PlanetSidePacket extends Serializable {
  def encode : Attempt[BitVector]
  def opcode : Enumeration#Value
}

/** Used by companion objects to create encoders and decoders */
trait Marshallable[T] {
  implicit val codec : Codec[T]
  def encode(a : T) : Attempt[BitVector] = codec.encode(a)
  def decode(a : BitVector) : Attempt[DecodeResult[T]] = codec.decode(a)
}

/** PlanetSide game packets: net.psforever.packet.game._ */
trait PlanetSideGamePacket extends PlanetSidePacket {
  def opcode : GamePacketOpcode.Type
}

/** PlanetSide control packets: net.psforever.packet.control._ */
trait PlanetSideControlPacket extends PlanetSidePacket {
  def opcode : ControlPacketOpcode.Type
}

/** PlanetSide crypto packets: net.psforever.packet.crypto._ */
trait PlanetSideCryptoPacket extends PlanetSidePacket {
  def opcode : CryptoPacketOpcode.Type
}


/** PlanetSide packet type. Used in more complicated packet headers
  *
  * ResetSequence - Not sure what this is used for or if the name matches what it actually does
  *                 but I saw some code that appeared to reset the packet sequence number when
  *                 this was set
  * Unknown2 - Your guess is as good as mine
  * Crypto - Used for early crypto packets that are NOT encrypted
  * Normal - Used for all non-crypto packets. May or may not be encrypted.
  *
  * Enumeration starts at 1. That's what I see in IDA */
object PacketType extends Enumeration(1) {
  type Type = Value
  val ResetSequence, Unknown2, Crypto, Normal = Value

  implicit val codec: Codec[this.Value] = PacketHelpers.createEnumerationCodec(this, uint4L)
}

/** PlanetSide packet flags (beginning of most packets) */
final case class PlanetSidePacketFlags(packetType : PacketType.Value, secured : Boolean)

/** Codec for [[PlanetSidePacketFlags]] */
object PlanetSidePacketFlags extends Marshallable[PlanetSidePacketFlags] {
  implicit val codec : Codec[PlanetSidePacketFlags] = (
      ("packet_type" | PacketType.codec) :: // first 4-bits
      ("unused" | constant(bin"0")) ::
      ("secured" | bool) ::
      ("advanced" | constant(bin"1")) :: // we only support "advanced packets"
      ("length_specified" | constant(bin"0")) // we DO NOT support this field
    ).as[PlanetSidePacketFlags]
}

//////////////////////////////////////////////////

object PacketHelpers {
  /** Used in certain instances where Codec defintions are stubbed out */
  def emptyCodec[T](instance : T) = {
    def to(pkt: T) = HNil
    def from(a: HNil) = instance
    Codec[HNil].xmap[T](from, to)
  }



  /** Create a Codec for an enumeration type that can correctly represent its value
    *
    * @param enum the enumeration type to create a codec for
    * @param storageCodec the Codec used for actually representing the value
    * @tparam E The inferred type
    * @return Generated codec
    */
  def createEnumerationCodec[E <: Enumeration](enum : E, storageCodec : Codec[Int]) : Codec[E#Value] = {
    type Struct = Int :: HNil
    val struct: Codec[Struct] = storageCodec.hlist
    val primitiveLimit = Math.pow(2, storageCodec.sizeBound.exact.get)

    // Assure that the enum will always be able to fit in a N-bit int
    assert(enum.maxId <= primitiveLimit,
      enum.getClass.getCanonicalName + s": maxId exceeds primitive type (limit of $primitiveLimit, maxId ${enum.maxId})")

    def to(pkt: E#Value): Struct = {
      pkt.id :: HNil
    }

    def from(struct: Struct): Attempt[E#Value] = struct match {
      case enumVal :: HNil =>
        // verify that this int can match the enum
        val first = enum.values.firstKey.id
        val last = enum.maxId-1

        if(enumVal >= first && enumVal <= last)
          Attempt.successful(enum(enumVal))
        else
          Attempt.failure(Err(s"Expected ${enum} with ID between [${first}, ${last}], but got '${enumVal}'"))
    }

    struct.narrow[E#Value](from, to)
  }

  /** Same as [[createEnumerationCodec]] but with a Codec type of Long
    *
    * NOTE: enumerations in scala can't be represented by more than an Int anyways, so this conversion shouldnt matter.
    * This is only to overload createEnumerationCodec to work with uint32[L] codecs (which are Long)
    */
  def createLongEnumerationCodec[E <: Enumeration](enum : E, storageCodec : Codec[Long]) : Codec[E#Value] = {
    createEnumerationCodec(enum, storageCodec.xmap[Int](_.toInt, _.toLong))
  }

  /** Common codec for how PlanetSide stores string sizes
    *
    * When the first bit of the byte is set, the size can be between [0, 127].
    * Otherwise, it is between [128, 32767] and two bytes are used for encoding.
    * The magic in this is next level (read as: SCodec makes things hard to understand)
    */
  def encodedStringSize : Codec[Int] = either(bool, uint(15), uint(7)).
    xmap[Int](
    (a : Either[Int, Int]) => a.fold[Int](a => a, a => a),
    (a : Int) =>
      // if the specified goes above 0x7f (127) then we need two bytes to represent it
      if(a > 0x7f) Left(a) else Right(a)
  )

  private def encodedStringSizeWithPad(pad : Int) : Codec[Int] = encodedStringSize <~ ignore(pad)

  /** Codec for how PlanetSide represents strings on the wire */
  def encodedString : Codec[String] = variableSizeBytes(encodedStringSize, ascii)


  /** Same as [[encodedString]] but with a bit adjustment
    *
    * This comes in handy when a PlanetSide string is decoded on a non-byte boundary. The PlanetSide client
    * will byte align after decoding the string lenght, but BEFORE the string itself. Scodec doesn't like this
    * variability and there doesn't appear to be a way to fix this issue.
    *
    * @param adjustment The adjustment amount in bits
    * @return Generated string decoding codec with adjustment
    */
  def encodedStringAligned(adjustment : Int) : Codec[String] = variableSizeBytes(encodedStringSizeWithPad(adjustment), ascii)

  /** Variable for the charset that PlanetSide uses for unicode (2 byte unicode) */
  val utf16 = string(Charset.forName("UTF-16LE"))

  /** Common codec for PlanetSide wchar_t strings (wide strings, UTF-16)
    *
    * An encoded *wide* string is twice the length of the given encoded size and half of the length of the
    * input string. We use xmap to transform the [[encodedString]] codec as this change is just a division and multiply
    */
  def encodedWideString : Codec[String] = variableSizeBytes(encodedStringSize.xmap(
    insize => insize*2, // number of symbols -> number of bytes (decode)
    outSize => outSize/2 // number of bytes -> number of symbols (encode)
  ), utf16)

  /** Same as [[encodedWideString]] but with a bit alignment after the decoded size
    */
  def encodedWideStringAligned(adjustment : Int) : Codec[String] = variableSizeBytes(encodedStringSizeWithPad(adjustment).xmap(
    insize => insize*2,
    outSize => outSize/2
  ), utf16)

  // TODO: make the function below work as there are places it should be used
  /*private def encodedStringSizeWithLimit(limit : Int) : Codec[Int] = {
    either(bool, uint(15), uint(7)).
      exmap[Int](
        (a : Either[Int, Int]) => {
          val result = a.fold[Int](a => a, a => a)

          if(result > limit)
            Attempt.failure(Err(s"Encoded string exceeded byte limit of $limit"))
          else
            Attempt.successful(result)
        },
        (a : Int) => {
          if(a > limit)
            return Attempt.failure(Err("adsf"))
          //return Left(Attempt.failure(Err(s"Encoded string exceeded byte limit of $limit")))

          if(a > 0x7f)
            return Attempt.successful(Left(a))
          else
            Right(a)
        }
    )
  }

  def encodedStringWithLimit(limit : Int) : Codec[String] = variableSizeBytes(encodedStringSizeWithLimit(limit), ascii)
  */
}