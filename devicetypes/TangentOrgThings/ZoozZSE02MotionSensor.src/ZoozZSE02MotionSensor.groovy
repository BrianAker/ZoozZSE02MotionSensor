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
	return "v1.17"
}

preferences 
{
	input("controller", "boolean", title: "Enable configuration, Smartthings is the primary controller of the device.", defaultValue: false)		 
	input("motionTimeout", "number", title: "Motion timeout in minutes (default 5 minutes)", defaultValue: 5)
}

metadata {
	definition (name: "Zooz ZSE02 Motion Sensor", namespace: "TangentOrgThings", author: "Brian Aker")
	{
		capability "Battery"
		capability "Configuration"
		capability "Motion Sensor"
		capability "Refresh"
		capability "Motion Sensor"
		capability "Tamper Alert"

		attribute "configured", "enum", ["unconfirgured", "configured", "reset"]
		attribute "MSR", "string"
		attribute "Manufacturer", "string"
		attribute "ManufacturerCode", "string"
		attribute "ProduceTypeCode", "string"
		attribute "ProductCode", "string"
		attribute "WakeUp", "string"
		attribute "WirelessConfig", "string"
		attribute "firmwareVersion", "string"
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
			state "clear", backgroundColor:"#00FF00"
			state("detected", backgroundColor:"#e51426")
		}
		valueTile("battery", "device.battery", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state("battery", label:'${currentValue}', unit:"%")
		}
		valueTile("driverVersion", "device.driverVersion", inactiveLabel: false, decoration: "flat", width: 2, height: 2) 
		{
			state("driverVersion", label: getDriverVersion())
		}
		standardTile("configure", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "default", label:"", action:"configuration.configure", icon:"st.secondary.configure"
		}
		standardTile("refresh", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "default", label:'', action: "refresh.refresh", icon: "st.secondary.refresh"
		}
		standardTile("reset", "device.switch", inactiveLabel: false, decoration: "flat", width: 2, height: 2)
		{
			state "configured", label:'', backgroundColor:"#ffffff"
			state "reset", label:'reset', backgroundColor:"#e51426"
		}
		main(["main"])
		details(["main", "tamper", "battery", "driverVersion", "configure", "refresh", "reset"])
	}
}

def installed()
{
	sendEvent([name: "driverVersion", value: getDriverVersion(), isStateChange: true])
	sendEvent([name: "configured", value: "unconfigured", isStateChange: true])
}

def updated()
{
	sendEvent(name: "driverVersion", value: getDriverVersion(), displayed:true)
	sendEvent(name: "configured", value: "false", displayed:true)
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

	result
}

def zwaveEvent(physicalgraph.zwave.commands.deviceresetlocallyv1.DeviceResetLocallyNotification cmd)
{
	log.info "Executing zwaveEvent 5A (DeviceResetLocallyV1) : 01 (DeviceResetLocallyNotification) with cmd: $cmd" 
		createEvent(name: "reset", value: "reset", descriptionText: cmd.toString(), isStateChange: true, displayed: true) 
} 

def zwaveEvent(physicalgraph.zwave.commands.manufacturerspecificv1.ManufacturerSpecificReport cmd) 
{
	def result = []

	def manufacturerCode = String.format("%04X", cmd.manufacturerId)
	def productTypeCode = String.format("%04X", cmd.productTypeId)
	def productCode = String.format("%04X", cmd.productId)
	def wirelessConfig = "ZWP"

	result << createEvent(name: "ManufacturerCode", value: manufacturerCode)
	result << createEvent(name: "ProduceTypeCode", value: productTypeCode)
	result << createEvent(name: "ProductCode", value: productCode)
	result << createEvent(name: "WirelessConfig", value: wirelessConfig)

	def msr = String.format("%04X-%04X-%04X", cmd.manufacturerId, cmd.productTypeId, cmd.productId)
	updateDataValue("MSR", msr)	updateDataValue("MSR", msr)
	updateDataValue("manufacturer", cmd.manufacturerName)
	if (!state.manufacturer) {
		state.manufacturer= cmd.manufacturerName
	}

	result << createEvent([name: "MSR", value: "$msr", descriptionText: "$device.displayName", isStateChange: false])
	result << createEvent([name: "Manufacturer", value: "${cmd.manufacturerName}", descriptionText: "$device.displayName", isStateChange: false])

	return result
}

def zwaveEvent(physicalgraph.zwave.commands.versionv1.VersionReport cmd) 
{
	log.info "Executing zwaveEvent 86 (VersionV1) with cmd: $cmd"
	def text = "$device.displayName: firmware version: ${cmd.applicationVersion}.${cmd.applicationSubVersion}, Z-Wave version: ${cmd.zWaveProtocolVersion}.${cmd.zWaveProtocolSubVersion}"
	createEvent([name: "firmwareVersion", value: "${cmd.applicationVersion}.${cmd.applicationSubVersion}", descriptionText: "$text", isStateChange: false])
}

def zwaveEvent(physicalgraph.zwave.commands.batteryv1.BatteryReport cmd)
{
	def results = []

	if (!isConfigured())
	{
		results << configure()
	}

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

	results << createEvent(map)

	return results
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

def zwaveEvent(physicalgraph.zwave.commands.alarmv2.AlarmReport cmd)
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

	return result
}

def zwaveEvent(physicalgraph.zwave.commands.associationv2.AssociationReport cmd)
{
	def result = []
	if (cmd.groupingIdentifier == 1)
	{
		if (cmd.nodeId.any { it == zwaveHubNodeId }) 
		{
			result << createEvent(descriptionText: "$device.displayName is associated in group ${cmd.groupingIdentifier}")
				result << createEvent(name: "configured", value: "configured", descriptionText: "$device.displayName not associated with hub", isStateChange: true)
		}
		else
		{
			result << response(zwave.associationV2.associationSet(groupingIdentifier:cmd.groupingIdentifier, nodeId:zwaveHubNodeId))
				result << response(zwave.associationV2.associationGet(groupingIdentifier:cmd.groupingIdentifier))
				result << createEvent(name: "configured", value: "unconfigured", descriptionText: "$device.displayName not associated with hub", isStateChange: true)
		}
	}

	return result
}

def zwaveEvent(physicalgraph.zwave.Command cmd)
{
	createEvent(descriptionText: cmd.toString(), isStateChange: false)
}

def refresh()
{
	log.debug "refresh() is called"

	def commands = [
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
		commands << zwave.versionv1.VersionGet().format()
	}
	response(delayBetween(commands, 6000))
}

def configure()
{
	refresh()
	response(delayBetween([
	zwave.associationV2.associationSet(groupingIdentifier:1, nodeId:zwaveHubNodeId).format(),
	zwave.associationV2.associationGet(groupingIdentifier:1).format()
	], 500))
}

private isConfigured()
{
	device.configured == "configured"
}
