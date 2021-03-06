// Copyright (c) 2017 PSForever
package net.psforever.objects

import net.psforever.objects.definition.EquipmentDefinition
import net.psforever.objects.definition.converter.LockerContainerConverter
import net.psforever.objects.equipment.{Equipment, EquipmentSize}
import net.psforever.objects.inventory.GridInventory

class LockerContainer extends Equipment {
  private val inventory = GridInventory(30, 20)

  def Inventory : GridInventory = inventory

  def Fit(obj : Equipment) : Option[Int] = inventory.Fit(obj.Definition.Tile)

  def Definition : EquipmentDefinition = GlobalDefinitions.locker_container
}

object LockerContainer {
  def apply() : LockerContainer = {
    new LockerContainer()
  }

  import net.psforever.packet.game.PlanetSideGUID
  def apply(guid : PlanetSideGUID) : LockerContainer = {
    val obj = new LockerContainer()
    obj.GUID = guid
    obj
  }
}
