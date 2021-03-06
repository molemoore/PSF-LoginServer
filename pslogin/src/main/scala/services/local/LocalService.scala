// Copyright (c) 2017 PSForever
package services.local

import akka.actor.{Actor, Props}
import services.local.support.{DoorCloseActor, HackClearActor}
import services.{GenericEventBus, Service}

class LocalService extends Actor {
  //import LocalService._
  private val doorCloser = context.actorOf(Props[DoorCloseActor], "local-door-closer")
  private val hackClearer = context.actorOf(Props[HackClearActor], "local-hack-clearer")
  private [this] val log = org.log4s.getLogger

  override def preStart = {
    log.info("Starting...")
  }

  val LocalEvents = new GenericEventBus[LocalServiceResponse]

  def receive = {
    case Service.Join(channel) =>
      val path = s"/$channel/LocalEnvironment"
      val who = sender()
      log.info(s"$who has joined $path")
      LocalEvents.subscribe(who, path)
    case Service.Leave() =>
      LocalEvents.unsubscribe(sender())
    case Service.LeaveAll() =>
      LocalEvents.unsubscribe(sender())

    case LocalServiceMessage(forChannel, action) =>
      action match {
        case LocalAction.DoorOpens(player_guid, zone, door) =>
          doorCloser ! DoorCloseActor.DoorIsOpen(door, zone)
          LocalEvents.publish(
            LocalServiceResponse(s"/$forChannel/LocalEnvironment", player_guid, LocalServiceResponse.DoorOpens(door.GUID))
          )
        case LocalAction.DoorCloses(player_guid, door_guid) =>
          LocalEvents.publish(
            LocalServiceResponse(s"/$forChannel/LocalEnvironment", player_guid, LocalServiceResponse.DoorCloses(door_guid))
          )
        case LocalAction.HackClear(player_guid, target, unk1, unk2) =>
          LocalEvents.publish(
            LocalServiceResponse(s"/$forChannel/LocalEnvironment", player_guid, LocalServiceResponse.HackClear(target.GUID, unk1, unk2))
          )
        case LocalAction.HackTemporarily(player_guid, zone, target, unk1, unk2) =>
          hackClearer ! HackClearActor.ObjectIsHacked(target, zone, unk1, unk2)
          LocalEvents.publish(
            LocalServiceResponse(s"/$forChannel/LocalEnvironment", player_guid, LocalServiceResponse.HackObject(target.GUID, unk1, unk2))
          )
        case LocalAction.TriggerSound(player_guid, sound, pos, unk, volume) =>
          LocalEvents.publish(
            LocalServiceResponse(s"/$forChannel/LocalEnvironment", player_guid, LocalServiceResponse.TriggerSound(sound, pos, unk, volume))
          )
        case _ => ;
      }

    //response from DoorCloseActor
    case DoorCloseActor.CloseTheDoor(door_guid, zone_id) =>
      LocalEvents.publish(
        LocalServiceResponse(s"/$zone_id/LocalEnvironment", Service.defaultPlayerGUID, LocalServiceResponse.DoorCloses(door_guid))
      )

    //response from HackClearActor
    case HackClearActor.ClearTheHack(target_guid, zone_id, unk1, unk2) =>
      LocalEvents.publish(
        LocalServiceResponse(s"/$zone_id/LocalEnvironment", Service.defaultPlayerGUID, LocalServiceResponse.HackClear(target_guid, unk1, unk2))
      )

    case msg =>
      log.info(s"Unhandled message $msg from $sender")
  }
}
