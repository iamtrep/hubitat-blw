/*
 * 
 *  IKEA Symfonisk Sound Controller E1744 Driver v1.06 (7th February 2022)
 *	
 */


import groovy.transform.Field

@Field boolean debugMode = true
@Field int reportIntervalMinutes = 50


metadata {

	definition (name: "IKEA Symfonisk Sound Controller E1744", namespace: "BirdsLikeWires", author: "Andrew Davison", importUrl: "https://raw.githubusercontent.com/birdslikewires/hubitat/master/ikea/drivers/ikea_symfonisk_sound_controller_e1744.groovy") {

		capability "Battery"
		capability "Configuration"
		capability "DoubleTapableButton"
		capability "HoldableButton"
		capability "Momentary"
		capability "PresenceSensor"
		capability "PushableButton"
		capability "Refresh"
		capability "ReleasableButton"
		capability "Switch"
		capability "SwitchLevel"

		attribute "batteryState", "string"
		attribute "batteryVoltage", "string"
		attribute "batteryVoltageWithUnit", "string"
		attribute "batteryWithUnit", "string"

		attribute "direction", "string"
		attribute "levelChange", "integer"

		if (debugMode) {
			command "checkPresence"
			command "testCommand"
		}

		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,1000", outClusters: "0003,0004,0006,0008,0019,1000", manufacturer: "IKEA of Sweden", model: "SYMFONISK Sound Controller", deviceJoinName: "IKEA Symfonisk Sound Controller", application: "21"
		fingerprint profileId: "0104", inClusters: "0000,0001,0003,0020,1000,FC7C", outClusters: "0003,0004,0005,0006,0008,0019,1000", manufacturer: "IKEA of Sweden", model: "SYMFONISK Sound Controller", deviceJoinName: "IKEA Symfonisk Sound Controller", application: "21"

	}

}


preferences {
	
	input name: "infoLogging", type: "bool", title: "Enable logging", defaultValue: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
	input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
	
}


def testCommand() {

	logging("${device} : Test Command", "info")

}


def installed() {
	// Runs after first installation.

	logging("${device} : Installed", "info")
	configure()

}


def configure() {

	// Tidy up.
	unschedule()

	state.clear()
	state.presenceUpdated = 0

	sendEvent(name: "level", value: 0, isStateChange: false)
	sendEvent(name: "presence", value: "present", isStateChange: false)

	// Schedule reporting and presence checking.
	int randomSixty

	//sendZigbeeCommands(zigbee.onOffConfig())
	int reportIntervalSeconds = reportIntervalMinutes * 60
	sendZigbeeCommands(zigbee.configureReporting(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021, DataType.UINT8, reportIntervalSeconds, reportIntervalSeconds, 0x00))   // Report in regardless of other changes.
	//sendZigbeeCommands(zigbee.enrollResponse())

	int checkEveryMinutes = 10
	randomSixty = Math.abs(new Random().nextInt() % 60)
	schedule("${randomSixty} 0/${checkEveryMinutes} * * * ? *", checkPresence)

	// Request application value, manufacturer, model name, software build and simple descriptor data.
	sendZigbeeCommands([
		"he rattr 0x${device.deviceNetworkId} 0x0001 0x0000 0x0001 {}",
		"he rattr 0x${device.deviceNetworkId} 0x0001 0x0000 0x0004 {}",
		"he rattr 0x${device.deviceNetworkId} 0x0001 0x0000 0x0005 {}",
		"he rattr 0x${device.deviceNetworkId} 0x0001 0x0000 0x4000 {}",
		"he raw ${device.deviceNetworkId} 0x0000 0x0000 0x0004 {00 ${zigbee.swapOctets(device.deviceNetworkId)} 01} {0x0000}"
	])

	// Set default preferences.
	device.updateSetting("infoLogging", [value: "true", type: "bool"])
	device.updateSetting("debugLogging", [value: "${debugMode}", type: "bool"])
	device.updateSetting("traceLogging", [value: "${debugMode}", type: "bool"])

	// Notify.
	sendEvent(name: "configuration", value: "success", isStateChange: false)
	logging("${device} : Configured", "info")

	updated()

}


void updated() {
	// Runs when preferences are saved.

	unschedule(debugLogOff)
	unschedule(traceLogOff)

	if (!debugMode) {
		runIn(2400,debugLogOff)
		runIn(1200,traceLogOff)
	}

	logging("${device} : Preferences Updated", "info")

	loggingStatus()

}


void refresh() {

	// Battery status can be requested if command is sent within about 3 seconds of an actuation.
	sendZigbeeCommands(zigbee.readAttribute(zigbee.POWER_CONFIGURATION_CLUSTER, 0x0021))
	logging("${device} : Refreshed", "info")

}


void push(buttonId) {
	
	sendEvent(name:"pushed", value: buttonId, isStateChange:true)
	
}


void hold(buttonId) {
	
	sendEvent(name:"held", value: buttonId, isStateChange:true)
	
}


void release(buttonId) {
	
	sendEvent(name:"released", value: buttonId, isStateChange:true)
	
}


void doubleTap(buttonId) {
	
	sendEvent(name:"doubleTapped", value: buttonId, isStateChange:true)
	
}

void off() {

	sendEvent(name: "switch", value: "off")
	sendEvent(name: "pushed", value: 1, isStateChange: true)
	logging("${device} : Switch : Off", "info")

}


void on() {

	sendEvent(name: "switch", value: "on")
	sendEvent(name: "pushed", value: 1, isStateChange: true)
	logging("${device} : Switch : On", "info")

}


void setLevel(BigDecimal level) {
	setLevel(level,1)
}


void setLevel(BigDecimal level, BigDecimal duration) {

	BigDecimal safeLevel = level <= 100 ? level : 100
	safeLevel = safeLevel < 0 ? 0 : safeLevel

	String hexLevel = percentageToHex(safeLevel.intValue())

	BigDecimal safeDuration = duration <= 25 ? (duration*10) : 255
	String hexDuration = Integer.toHexString(safeDuration.intValue())

	String pluralisor = duration == 1 ? "" : "s"
	logging("${device} : setLevel : Got level request of '${level}' (${safeLevel}%) [${hexLevel}] over '${duration}' (${safeDuration} decisecond${pluralisor}) [${hexDuration}].", "debug")

	sendEvent(name: "level", value: "${safeLevel}")

}


void parse(String description) {
	// Primary parse routine.

	logging("${device} : parse() : $description", "trace")

	updatePresence()

	Map descriptionMap = zigbee.parseDescriptionAsMap(description)

	if (descriptionMap) {

		processMap(descriptionMap)

	} else {
		
		logging("${device} : Parse : Failed to parse received data. Please report these messages to the developer.", "warn")
		logging("${device} : Splurge! : ${description}", "warn")

	}

}


void processMap(Map map) {

	logging("${device} : processMap() : ${map}", "trace")

	String[] receivedData = map.data

	if (map.cluster == "0001") { 

		if (map.attrId == "0021") {

			// Okay, battery reporting on this thing is weird. It's not a voltage, but a percentage.
			// According to the Zigbee Cluster User Guide, the decimal value from the hex should be multiplied by 0.5 to give percent.
			// But the readings are all over the shop. Might be worth pairing to the Tradfri hub and checking for firmware updates?

			// Hey, in the meantime, let's just fudge it!
			state.batteryOkay = true

			// Report the battery... percentage.
			def batteryHex = "undefined"
			BigDecimal batteryPercentage = 0

			batteryHex = map.value
			logging("${device} : batteryHex : ${batteryHex}", "trace")

			batteryPercentage = zigbee.convertHexToInt(batteryHex)
			logging("${device} : batteryPercentage sensor value : ${batteryPercentage}", "debug")

			//batteryPercentage = batteryPercentage * 0.5
			batteryPercentage = batteryPercentage.setScale(0, BigDecimal.ROUND_HALF_UP)
			batteryPercentage = batteryPercentage > 100 ? 100 : batteryPercentage
			batteryPercentage = batteryPercentage < 0 ? 0 : batteryPercentage

			//Integer batteryDifference = Math.abs(device.currentState("battery").value.toInteger() - batteryPercentage)

			logging("${device} : batteryPercentage : ${batteryPercentage}", "debug")
			sendEvent(name: "battery", value:batteryPercentage, unit: "%")
			sendEvent(name: "batteryWithUnit", value:"${batteryPercentage} %")
			sendEvent(name: "batteryState", value: "discharging")

		} else {

			logging("${device} : Skipped : Power Cluster with no data.", "debug")

		}

	} else if (map.clusterId == "0001") { 

		logging("${device} : Skipped : Power Cluster with no data.", "debug")

	} else if (map.clusterId == "0006") { 

		if (map.command == "02") {

			parsePress(map)

		} else {

			logging("${device} : Skipped : On/Off Cluster with extraneous data.", "debug")

		}

	} else if (map.clusterId == "0008") {

		parsePress(map)

	} else if (map.clusterId == "0013") {

		logging("${device} : Skipped : Device Announce Broadcast", "debug")

	} else if (map.clusterId == "0500") {

		logging("${device} : Skipped : IAS Zone", "debug")

	} else if (map.clusterId == "8004") {

		processDescriptors(map)

	} else if (map.clusterId == "8021") {

		logging("${device} : Skipped : Bind Response", "debug")

	} else if (map.clusterId == "8022") {

		logging("${device} : Skipped : Unbind Response", "debug")

	} else if (map.cluster == "0000") {

		processBasic(map)

	} else {

		reportToDev(map)

	}

}


@Field static Boolean isParsing = false
def parsePress(Map map) {

	logging("${device} : parsePress() : Called!", "debug")

	if (isParsing) return
	isParsing = true

	// We'll figure out the button numbers in a tick.
	int buttonNumber = 0

	if (map.clusterId == "0006") { 

		// This is a press of the button.
		buttonNumber = 1

		logging("${device} : Trigger : Button ${buttonNumber} Pressed", "info")
		sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
		
		device.currentState("switch").value == "off" ? on() : off()

	} else if (map.clusterId == "0008") {

		if (map.command == "01") {

			// This is a turn of the dial starting.
			buttonNumber = 2

			String[] receivedData = map.data

			if (receivedData[0] == "00") {

				buttonNumber = 2
				state.changeLevelStart = now()
				logging("${device} : Trigger : Dial (Button ${buttonNumber}) Turning Clockwise", "info")
				sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
				sendEvent(name: "direction", value: "clockwise")
				sendEvent(name: "held", value: buttonNumber, isStateChange: true)
				sendEvent(name: "switch", value: "on", isStateChange: false)

			} else if (receivedData[0] == "01") {

				buttonNumber = 3
				state.changeLevelStart = now()
				logging("${device} : Trigger : Dial (Button ${buttonNumber}) Turning Anticlockwise", "info")
				sendEvent(name: "pushed", value: buttonNumber, isStateChange: true)
				sendEvent(name: "direction", value: "anticlockwise")
				sendEvent(name: "held", value: buttonNumber, isStateChange: true)
				sendEvent(name: "switch", value: "on", isStateChange: false)

			} else {

				reportToDev(map)

			}

		} else if (map.command == "02") {

			// This is a multi-press of the button.
			buttonNumber = 1

			String[] receivedData = map.data

			if (receivedData[0] == "00") {

				// Double-press is a supported Hubitat action, so just report the event.
				logging("${device} : Trigger : Button ${buttonNumber} Double Pressed", "info")
				sendEvent(name: "doubleTapped", value: buttonNumber, isStateChange: true)

			} else if (receivedData[0] == "01") {

				// Triple-pressing is not a supported Hubitat action, but this device doesn't support hold or release on the button, so we'll use "held" for this.
				logging("${device} : Trigger : Button ${buttonNumber} Triple Pressed", "info")
				sendEvent(name: "held", value: buttonNumber, isStateChange: true)

			} else {

				reportToDev(map)

			}

		} else if (map.command == "03") {

			// This is a turn of the dial stopping.
			// There's no differentiation in the data sent, so we work out from which direction we're stopping using the previous hold state.

			buttonNumber = device.currentState("held").value.toInteger()

			logging("${device} : Trigger : Dial (Button ${buttonNumber}) Stopped", "info")
			sendEvent(name: "released", value: buttonNumber, isStateChange: true)

			// Now work out the level we should change to based upon the time spent changing.

			Integer initialLevel = device.currentState("level").value.toInteger()

			long millisTurning = now() - state.changeLevelStart
			if (millisTurning > 6000) {
				millisTurning = 0				// In case the messages don't stop we could end up at full brightness or VOLUME!
			}

			BigInteger levelChange = 0
			levelChange = millisTurning / 6000 * 100

			BigDecimal targetLevel = 0

			if (buttonNumber == 2) {

				targetLevel = device.currentState("level").value.toInteger() + levelChange

			} else {

				targetLevel = device.currentState("level").value.toInteger() - levelChange
				levelChange *= -1

			}

			logging("${device} : Level : Dial (Button ${buttonNumber}) - Changing from initialLevel '${initialLevel}' by levelChange '${levelChange}' after millisTurning for ${millisTurning} ms.", "debug")

			sendEvent(name: "levelChange", value: levelChange, isStateChange: true)

			setLevel(targetLevel)

		} else {

			reportToDev(map)

		}

	}

	pauseExecution 110
	isParsing = false

}


// Library v1.02 (12th January 2022)


void sendZigbeeCommands(List<String> cmds) {
	// All hub commands go through here for immediate transmission and to avoid some method() weirdness.

    logging("${device} : sendZigbeeCommands received : ${cmds}", "trace")
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))

}


void updatePresence() {

	long millisNow = new Date().time
	state.presenceUpdated = millisNow
	sendEvent(name: "presence", value: "present")

}


void checkPresence() {
	// Check how long ago the presence state was updated.

	int uptimeAllowanceMinutes = 20			// The hub takes a while to settle after a reboot.

	if (state.presenceUpdated > 0) {

		long millisNow = new Date().time
		long millisElapsed = millisNow - state.presenceUpdated
		long presenceTimeoutMillis = ((reportIntervalMinutes * 2) + 20) * 60000
		long reportIntervalMillis = reportIntervalMinutes * 60000
		BigInteger secondsElapsed = BigDecimal.valueOf(millisElapsed / 1000)
		BigInteger hubUptime = location.hub.uptime

		if (millisElapsed > presenceTimeoutMillis) {

			if (hubUptime > uptimeAllowanceMinutes * 60) {

				sendEvent(name: "presence", value: "not present")
				logging("${device} : Presence : Not Present! Last report received ${secondsElapsed} seconds ago.", "warn")

			} else {

				logging("${device} : Presence : Ignoring overdue presence reports for ${uptimeAllowanceMinutes} minutes. The hub was rebooted ${hubUptime} seconds ago.", "debug")

			}

		} else {

			sendEvent(name: "presence", value: "present")
			logging("${device} : Presence : Last presence report ${secondsElapsed} seconds ago.", "debug")

		}

		logging("${device} : checkPresence() : ${millisNow} - ${state.presenceUpdated} = ${millisElapsed}", "trace")
		logging("${device} : checkPresence() : Report interval is ${reportIntervalMillis} ms, timeout is ${presenceTimeoutMillis} ms.", "trace")

	} else {

		logging("${device} : Presence : Waiting for first presence report.", "warn")

	}

}


void processBasic(Map map) {
	// Process the basic descriptors normally received from Zigbee Cluster 0000 into device data values.

	if (map.attrId == "0001") {

		updateDataValue("application", "${map.value}")
		logging("${device} : Application : ${map.value}", "debug")

	} else if (map.attrId == "0004") {

		updateDataValue("manufacturer", map.value)
		logging("${device} : Manufacturer : ${map.value}", "debug")

	} else if (map.attrId == "0005") {

		updateDataValue("model", map.value)
		logging("${device} : Model : ${map.value}", "debug")

	} else if (map.attrId == "4000") {

		updateDataValue("softwareBuild", "${map.value}")
		logging("${device} : Firmware : ${map.value}", "debug")

	}

}


void processDescriptors(Map map) {
	// Process the simple descriptors normally received from Zigbee Cluster 8004 into device data values.

	String[] receivedData = map.data

	if (receivedData[1] == "00") {
		// Received simple descriptor data.

		//updateDataValue("endpointId", receivedData[5])						// can lead to a weird duplicate
		updateDataValue("profileId", receivedData[6..7].reverse().join())

		Integer inClusterNum = Integer.parseInt(receivedData[11], 16)
		Integer position = 12
		Integer positionCounter = null
		String inClusters = ""
		if (inClusterNum > 0) {
			(1..inClusterNum).each() {b->
				positionCounter = position+((b-1)*2)
				inClusters += receivedData[positionCounter..positionCounter+1].reverse().join()
				if (b < inClusterNum) {
					inClusters += ","
				}
			}
		}
		position += inClusterNum*2
		Integer outClusterNum = Integer.parseInt(receivedData[position], 16)
		position += 1
		String outClusters = ""
		if (outClusterNum > 0) {
			(1..outClusterNum).each() {b->
				positionCounter = position+((b-1)*2)
				outClusters += receivedData[positionCounter..positionCounter+1].reverse().join()
				if (b < outClusterNum) {
					outClusters += ","
				}
			}
		}

		updateDataValue("inClusters", inClusters)
		updateDataValue("outClusters", outClusters)

		logging("${device} : Received $inClusterNum inClusters : $inClusters", "debug")
		logging("${device} : Received $outClusterNum outClusters : $outClusters", "debug")

	} else {

		reportToDev(map)

	}

}


void reportToDev(map) {

	String[] receivedData = map.data

	def receivedDataCount = ""
	if (receivedData != null) {
		receivedDataCount = "${receivedData.length} bits of "
	}

	logging("${device} : UNKNOWN DATA! Please report these messages to the developer.", "warn")
	logging("${device} : Received : endpoint: ${map.endpoint}, cluster: ${map.cluster}, clusterId: ${map.clusterId}, attrId: ${map.attrId}, command: ${map.command} with value: ${map.value} and ${receivedDataCount}data: ${receivedData}", "warn")
	logging("${device} : Splurge! : ${map}", "trace")

}


@Field static Boolean debouncingParentState = false
void debounceParentState(String attribute, String state, String message, String level, Integer duration) {

	if (debouncingParentState) return
	debouncingParentState = true

	sendEvent(name: "$attribute", value: "$state")
	logging("${device} : $message", "$level")

	pauseExecution duration
	debouncingParentState = false

}


def fetchChild(String type, String endpoint) {
	// Creates and retrieves child devices matched to endpoints.

	def childDevice = getChildDevice("${device.id}-${endpoint}")

	if (endpoint != "null") {

		if (!childDevice) {

			logging("${device} : Creating child device $device.id-$endpoint", "debug")

			childDevice = addChildDevice("hubitat", "Generic Component ${type}", "${device.id}-${endpoint}", [name: "${device.displayName} ${type} ${endpoint}", label: "${device.displayName} ${type} ${endpoint}", isComponent: false])

			if (type == "Switch") {

				// We could use this as an opportunity to set all the relays to a known state, but we don't. Just in case.
				childDevice.parse([[name: "switch", value: 'off']])

			} else {

				logging("${device} : fetchChild() : I don't know what to do with the '$type' device type.", "error")

			}

			childDevice.updateSetting("txtEnable", false)

		}

		logging("${device} : Retrieved child device $device.id-$endpoint", "debug")

	} else {

		logging("${device} : Received null endpoint for device $device.id", "error")

	}

	return childDevice

}


def fetchChildStates(String state, String requestor) {
	// Retrieves requested states of child devices.

	logging("${device} : fetchChildStates() : Called by $requestor", "debug")

	def childStates = []
	def children = getChildDevices()

	children.each {child->

		// Give things a chance!
		pauseExecution(100)
	
		// Grabs the requested state from the child device.
		String childState = child.currentValue("${state}")

		if ("${child.id}" != "${requestor}") {
			// Don't include the requestor's state in the results, as we're likely in the process of updating it.
			childStates.add("${childState}")
			logging("${device} : fetchChildStates() : Found $child.id is '$childState'", "debug")
		}

	}

	return childStates

}


void deleteChildren() {
	// Deletes children we may have created.

	logging("${device} : deleteChildren() : Deleting rogue children.", "debug")

	def children = getChildDevices()
    children.each {child->
  		deleteChildDevice(child.deviceNetworkId)
    }

}


void componentRefresh(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentRefresh() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he rattr 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x00 {}"])

}


void componentOn(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentOn() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x01 {}"])

}


void componentOff(com.hubitat.app.DeviceWrapper childDevice) {

	logging("componentOff() from $childDevice.deviceNetworkId", "debug")
	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${childDevice.deviceNetworkId.split("-")[1]} 0x0006 0x00 {}"])

}


private String flipLittleEndian(Map map, String attribute) {

	String bigEndianAttribute = ""
	for (int v = map."${attribute}".length(); v > 0; v -= 2) {
		bigEndianAttribute += map."${attribute}".substring(v - 2, v)
	}
	return bigEndianAttribute

}


private String[] millisToDhms(int millisToParse) {

	long secondsToParse = millisToParse / 1000

	def dhms = []
	dhms.add(secondsToParse % 60)
	secondsToParse = secondsToParse / 60
	dhms.add(secondsToParse % 60)
	secondsToParse = secondsToParse / 60
	dhms.add(secondsToParse % 24)
	secondsToParse = secondsToParse / 24
	dhms.add(secondsToParse % 365)
	return dhms

}


private String percentageToHex(Integer pc) {

	BigDecimal safePc = pc > 0 ? (pc*2.55) : 0
	safePc = safePc > 255 ? 255 : safePc
	return Integer.toHexString(safePc.intValue())

}


private Integer hexToPercentage(String hex) {

	String safeHex = hex.take(2)
    Integer pc = Integer.parseInt(safeHex, 16) << 21 >> 21
	return pc / 2.55

}


private BigDecimal hexToBigDecimal(String hex) {

    int d = Integer.parseInt(hex, 16) << 21 >> 21
    return BigDecimal.valueOf(d)

}


private String hexToBinary(String thisByte, Integer size = 8) {

	String binaryValue = new BigInteger(thisByte, 16).toString(2);
	return String.format("%${size}s", binaryValue).replace(' ', '0')
	
}


void traceLogOff(){
	
	log.trace "${device} : Trace Logging : Automatically Disabled"
	device.updateSetting("traceLogging",[value:"false",type:"bool"])

}

void debugLogOff(){
	
	log.debug "${device} : Debug Logging : Automatically Disabled"
	device.updateSetting("debugLogging",[value:"false",type:"bool"])

}


void infoLogOff(){
	
	log.info "${device} : Info  Logging : Automatically Disabled"
	device.updateSetting("infoLogging",[value:"false",type:"bool"])

}


private boolean logging(String message, String level) {

	boolean didLog = false

	if (level == "error") {
		log.error "$message"
		didLog = true
	}

	if (level == "warn") {
		log.warn "$message"
		didLog = true
	}

	if (traceLogging && level == "trace") {
		log.trace "$message"
		didLog = true
	}

	if (debugLogging && level == "debug") {
		log.debug "$message"
		didLog = true
	}

	if (infoLogging && level == "info") {
		log.info "$message"
		didLog = true
	}

	return didLog

}
