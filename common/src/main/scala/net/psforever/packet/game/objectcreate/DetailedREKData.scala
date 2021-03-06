// Copyright (c) 2017 PSForever
package net.psforever.packet.game.objectcreate

import net.psforever.packet.Marshallable
import scodec.{Attempt, Codec, Err}
import scodec.codecs._
import shapeless.{::, HNil}

/**
  * A representation of the REK portion of `ObjectCreateDetailedMessage` packet data.
  * This data will help construct the "tool" called a Remote Electronics Kit.<br>
  * <br>
  * Of note is the first portion of the data which resembles the `DetailedWeaponData` format.
  * @param unk1 na
  * @param unk2 na
  */
final case class DetailedREKData(unk1 : Int,
                                 unk2 : Int = 0
                                ) extends ConstructorData {
  override def bitsize : Long = 67L
}

object DetailedREKData extends Marshallable[DetailedREKData] {
  implicit val codec : Codec[DetailedREKData] = (
    ("unk" | uint4L) ::
      uint4L ::
      uintL(20) ::
      uint4L ::
      uint16L ::
      uint4L ::
      ("unk2" | uintL(15))
    ).exmap[DetailedREKData] (
    {
      case code :: 8 :: 0 :: 2 :: 0 :: 8 :: unk2 :: HNil =>
        Attempt.successful(DetailedREKData(code, unk2))
      case _ =>
        Attempt.failure(Err("invalid rek data format"))
    },
    {
      case DetailedREKData(code, unk2) =>
        Attempt.successful(code :: 8 :: 0 :: 2 :: 0 :: 8 :: unk2 :: HNil)
    }
  )
}
