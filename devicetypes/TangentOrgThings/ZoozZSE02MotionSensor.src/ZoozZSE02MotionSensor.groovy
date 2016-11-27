// vim :set ts=2 sw=2 sts=2 expandtab smarttab :
/**
 *  Zooz ZSE02 Motion Sensor
 *
 *  Copyright 2016 Brian Aker
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 */

def getDriverVersion()
{
	return "v1.10"
}

metadata {
	definition (name: "Zooz ZSE02 Motion Sensor", namespace: "TangentOrgThings", author: "Brian Aker")
	{
		capability "Battery"
		capability "Configuration"
		capability "Motion Sensor"
		capability "Refresh"
		capability "Tamper Alert"
	}

	// zw:S type:0701 mfr:0152 prod:0500 model:0003 ver:0.01 zwv:3.95 lib:06 cc:5E,85,59,71,80,5A,73,84,72,86 role:06 ff:8C07 ui:8C07
	fingerprint deviceId: "0x0701", mfr: "0152", prod: "0500", model: "0003", ver: "0.01", inClusters: "0x5E 0x85 0x59 0x71 0x80 0x5A 0x73 0x84 0x72 0x86"


	simulator 
	{
		// TODO: define status and reply messages here
	}

	tiles (scale: 2)
	{
		multiAttributeTile(name:"main", type: "generic", width: 6, height: 4)
		{
			tileAttribute ("device.motion", key: "PRIMARY_CONTROL")
			{
				attributeState "active", label:'motion', icon:"st.motion.motion.active", backgroundColor:"#53a7c0"
				attributeState "inactive", label:'no motion', icon:"st.motion.motion.inactive", backgroundColor:"#ffffff"
			}
		}
		valueTile("tamper", "device.tamper", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "clear", label:'NO TAMPER', backgroundColor:"#ff0000"
			state("detected", label:'TAMPER DETECTED', backgroundColor:"#53a7c0")
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state("battery", label:'${currentValue}% battery', unit:"")
		}
		valueTile("driverVersion", "device.driverVersion", inactiveLabel: false, decoration: "flat", width: 2, height: 2) 
		{
			state("driverVersion", label:'${currentValue}')
		}
		standardTile("configure", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "default", label:"", action:"configure", icon:"st.secondary.configure"
		}
		standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "default", label:'', action: "refresh.refresh", icon: "st.secondary.refresh"
		}
		main(["main"])
		details(["main", "tamper", "battery", "driverVersion", "configure", "refresh"])
	}
}

def updated()
{
	updateDataValue("configured", "false")
}

def parse(String description)
{
	def result = null
	if (description != "updated") 
	{  
		def cmd = zwave.parse(description)
		if (cmd) {
			result = zwaveEvent(cmd)
		}
		else
		{
			log.debug "Non-parsed event: ${description}"
		}
	}
	return result
}

def zwaveEvent(physicalgraph.zwave.commands.wakeupv2.WakeUpNotification cmd)
{
	def result = createEvent(descriptionText: "${device.displayName} woke up", displayed: true)
	def cmds = []
	if (!isConfigured())
	{
		// we're still in the process of configuring a newly joined device
		configure()
	}
	else if (isConfigured())
	{
		if (!state.lastbat || (new Date().time) - state.lastbat > 53*60*60*1000)
		{
			result << response(zwave.batteryV1.batteryGet())
		}
	}
	else
	{
		result << response(zwave.wakeUpV1.wakeUpNoMoreInformation())
	}
	
	result
}

def zwaveEvent(physicalgraph.zwave.commands.deviceresetlocallyv1.DeviceResetLocallyNotification cmd)
{
  log.info "Executing zwaveEvent 5A (DeviceResetLocallyV1) : 01 (DeviceResetLocallyNotification) with cmd: $cmd" 
  createEvent(descriptionText: cmd.toString(), isStateChange: true, displayed: true) 
} 

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv2.ManufacturerSpecificReport cmd) 
{
	def result = []

	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	log.debug "msr: $msr"
	updateDataValue("MSR", msr)

	result << createEvent(descriptionText: "$device.displayName MSR: $msr", isStateChange: false)
	result
}

def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) 
{
  log.info "Executing zwaveEvent 86 (VersionV1) with cmd: $cmd"
  def fw = "${cmd.applicationVersion}.${cmd.applicationSubVersion}"
  updateDataValue("fw", fw)
  updateDataValue("firmwareVersion", fw)
  def text = "$device.displayName: firmware version: $fw, Z-Wave version: ${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}"
  createEvent(descriptionText: text, isStateChange: false)
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd)
{
	def map = [ name: "battery", unit: "%" ]
	if (cmd.batteryLevel == 0xFF)
	{
		map.value = 1
		map.descriptionText = "${device.displayName} has a low battery"
		map.isStateChange = true
	}
	else 
	{
		map.value = cmd.batteryLevel
	}
	state.lastbat = new Date().time

	if (!isConfigured())
	{
		configure()
	}
	else
	{
		[createEvent(map), response(zwave.wakeUpV1.wakeUpNoMoreInformation())]
	}
}

def motionEvent(value)
{
	def map = [name: "motion"]
	if (value != 0)
	{
		map.value = "active"
		map.descriptionText = "$device.displayName detected motion"
	}
	else
	{
		map.value = "inactive"
		map.descriptionText = "$device.displayName motion has stopped"
	}
	createEvent(map)
}

def zwaveEvent(physicalgraph.zwave.commands.basicv1.BasicSet cmd)
{
	motionEvent(cmd.value)
}

def zwaveEvent(physicalgraph.zwave.commands.notificationv3.NotificationReport cmd)
{
	def result = []
	if (cmd.notificationType == 0x07)
	{
		if (cmd.event == 0x00)
		{
			if (cmd.eventParameter == [8])
			{
				result << motionEvent(0)
			}
			else if (cmd.eventParameter == [3])
			{
				result << createEvent(descriptionText: "$device.displayName covering replaced", isStateChange: true, displayed: false)
				result << createEvent(name: "tamper", value: "detected", descriptionText: "$device.displayName covering was removed", isStateChange: true)
			}
			else
			{
				result << motionEvent(0)
			}
		}
		else if (cmd.event == 0x03)
		{
			result << createEvent(name: "acceleration", value: "active", descriptionText: "$device.displayName has been deactivated by the switch.")
		}
		else if (cmd.event == 0x08)
		{
			result << motionEvent(255)
		}
	}
	else
	{
		result << createEvent(descriptionText: cmd.toString(), isStateChange: true)
	}

	result
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd)
{
	def result = []
	if (cmd.groupingIdentifier == 1)
	{
		if (cmd.nodeId.any { it == zwaveHubNodeId }) 
		{
			result << createEvent(descriptionText: "$device.displayName is associated in group ${cmd.groupingIdentifier}")
			setConfigured("Group1", true)
		}
		else
		{
			setConfigured("Group1", false)
			result << createEvent(descriptionText: "Associating $device.displayName in group ${cmd.groupingIdentifier}")
			result << response(zwave.associationV2.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
			result << response(zwave.associationV2.associationGet(groupingIdentifier:cmd.groupingIdentifier))
		}
	}
	else
	{
		result << createEvent(descriptionText: "$device.displayName lacks group $cmd.groupingIdentifier")
	}
}

def zwaveEvent(physicalgraph.zwave.Command cmd)
{
	createEvent(descriptionText: cmd.toString(), isStateChange: false)
}

def refresh()
{
	log.debug "refresh() is called"

	def commands = [
	zwave.switchBinaryV1.switchBinaryGet().format(),
	zwave.notificationv3.NotificationGet.format(),
	zwave.batteryV1.batteryGet().format(),
	zwave.associationV2.associationGet(groupingIdentifier:1).format()
	]
	if (getDataValue("MSR") == null)
	{
		commands << zwave.manufacturerSpecificV2.manufacturerSpecificGet().format()
	}
	if (device.currentState('firmwareVersion') == null)
	{
		commands << zwave.versionV2.versionGet().format()
	}
	delayBetween(commands, 6000)
}

def configure()
{
	updateDataValue("driverVersion", getDriverVersion())

	setConfigured("Group1", false)
	delayBetween([
		// Can use the zwaveHubNodeId variable to add the hub to the device's associations:
		zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:[zwaveHubNodeId]).format()
	], 500)	
	refresh()

  sendEvent(name: "driverVersion", value: getDriverVersion(), displayed:true)
}

private setConfigured(String set_param, Boolean setConf)
{
	if ( setConf )
  {
		updateDataValue(set_param, "true")
		updateDataValue("configured", "true")
	}
	else
  {
		updateDataValue("Group1", "false")
		updateDataValue("configured", "false")
	}
	return []
}

private isConfigured()
{
	getDataValue("configured") == "true"
}
