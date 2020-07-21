/*
 * 
 *  AlertMe Power Clamp Driver v1.03 (21st July 2020)
 *	
 */


metadata {

	definition (name: "AlertMe Power Clamp", namespace: "AlertMe", author: "Andrew Davison") {

		capability "Battery"
		capability "Initialize"
		capability "Power Meter"
		capability "Refresh"
		capability "Temperature Measurement"

		command "normalMode"
		command "rangingMode"

		attribute "batteryVoltage", "string"
		attribute "batteryVoltageWithUnit", "string"
		attribute "batteryWithUnit", "string"
		attribute "mode", "string"
		attribute "powerWithUnit", "string"
		attribute "rssi", "string"
		attribute "temperatureWithUnit", "string"
		attribute "uptime", "string"
		attribute "usage", "string"
		attribute "usageWithUnit", "string"

		fingerprint profileId: "C216", inClusters: "00F0,00F3,00EF", outClusters: "", manufacturer: "AlertMe.com", model: "Power Clamp", deviceJoinName: "AlertMe Power Clamp"
		
	}

}


preferences {
	
	input name: "batteryVoltageMinimum",type: "decimal",title: "Battery Minimum Voltage",description: "Low battery voltage (default: 2.5)",defaultValue: "2.5",range: "2.1..2.8"
	input name: "batteryVoltageMaximum",type: "decimal",title: "Battery Maximum Voltage",description: "Full battery voltage (default: 3.0)",defaultValue: "3.0",range: "2.9..3.4"
	input name: "infoLogging",type: "bool",title: "Enable logging",defaultValue: true
	input name: "debugLogging",type: "bool",title: "Enable debug logging",defaultValue: false
	input name: "silenceLogging",type: "bool",title: "Force silent mode (overrides log settings)",defaultValue: false
	
}


def installed() {
	// Runs after pairing.
	logging("${device} : Installing",100)
}


def initialize() {
	configure()
}


def configure() {
	// Runs after installed() whenever a device is paired or rejoined.

	state.batteryInstalled = false
	state.operatingMode = "normal"
	state.rangingPulses = 0

	device.updateSetting("infoLogging",[value:"true",type:"bool"])
	device.updateSetting("debugLogging",[value:"false",type:"bool"])
	device.updateSetting("silenceLogging",[value:"false",type:"bool"])

	// Remove any scheduled events.
	unschedule()

	// Bunch of zero or null values.
	sendEvent(name:"battery",value:0, unit: "%", isStateChange: false)
	sendEvent(name:"batteryVoltage", value: 0, unit: "V", isStateChange: false)
	sendEvent(name:"batteryVoltageWithUnit", value: "unknown", isStateChange: false)
	sendEvent(name:"batteryWithUnit", value: "unknown",isStateChange: false)
	sendEvent(name:"mode", value: "unknown",isStateChange: false)
	sendEvent(name:"power", value: 0, unit: "W", isStateChange: false)
	sendEvent(name:"powerWithUnit", value: "unknown", isStateChange: false)
	sendEvent(name:"rssi", value: "unknown")
	sendEvent(name:"temperature", value: 0, unit: "C", isStateChange: false)
	sendEvent(name:"temperatureWithUnit", value: "unknown", isStateChange: false)
	sendEvent(name:"uptime", value: 0, unit: "s", isStateChange: false)
	sendEvent(name:"usage", value: 0, unit: "Wh", isStateChange: false)
	sendEvent(name:"usageWithUnit", value: "unknown", isStateChange: false)

	// Schedule our refresh check-in and turn off the logs.
	randomMinute = Math.abs(new Random().nextInt() % 60)
	schedule("0 ${randomMinute} 0/1 1/1 * ? *", rangeAndRefresh)
	runIn(300,debugLogOff)
	runIn(600,infoLogOff)

	// Report our logging status.
	loggingStatus()

	// Set the operating mode.
	rangingMode()
	runIn(6,normalMode)

	// All done.
	logging("${device} : Configured",100)
	
}


def updated() {
	// Runs whenever preferences are saved.
	loggingStatus()
	refresh()
}


void loggingStatus() {
	logging("${device} : Logging : ${infoLogging == true}",200)
	logging("${device} : Debug Logging : ${debugLogging == true}",200)
	logging("${device} : Silent Mode : ${silenceLogging == true}",200)
}


void reportToDev(data,map) {

	logging("${device} : Unknown data! Please report this to the developer.",201)
	logging("${device} : Received clusterId ${map.clusterId} command ${map.command} with ${data.length} values: ${data}",201)
	logging("${device} : Splurge! ${map}",201)

}


void debugLogOff(){
	
	logging("${device} : Debug Logging : false",200)
	device.updateSetting("debugLogging",[value:"false",type:"bool"])

}


void infoLogOff(){
	
	logging("${device} : Logging : false",200)
	device.updateSetting("infoLogging",[value:"false",type:"bool"])

}


def normalMode() {

	// This is the standard, quite chatty, running mode of the outlet.

	def someCommand = []
	someCommand.add("he raw ${device.deviceNetworkId} 0 2 0x00F0 {11 00 FA 00 01} {0xC216}")
	sendHubCommand(new hubitat.device.HubMultiAction(someCommand, hubitat.device.Protocol.ZIGBEE))
	refresh()
	state.operatingMode = "normal"
	sendEvent(name: "mode", value: "normal")
	logging("${device} : Mode : Normal",100)

}


def rangingMode() {

	// Ranging mode double-flashes (good signal) or triple-flashes (poor signal) the indicator
	// while reporting RSSI values. It's also a handy means of identifying or pinging a device.

	// Don't set state.operatingMode here! Ranging is a temporary state only.

	def someCommand = []
	someCommand.add("he raw ${device.deviceNetworkId} 0 2 0x00F0 {11 00 FA 01 01} {0xC216}")
	sendHubCommand(new hubitat.device.HubMultiAction(someCommand, hubitat.device.Protocol.ZIGBEE))
	sendEvent(name: "mode", value: "ranging")
	logging("${device} : Mode : Ranging",100)

	// Ranging will be disabled after a maximum of 30 pulses.
	state.rangingPulses = 0

}


void refresh() {

	// The Smart Plug becomes active after joining once it has received this status update request.
	// It also expects the Hub to check in with this occasionally, otherwise remote control is dropped. 

	def stateRequest = []
	stateRequest.add("he raw ${device.deviceNetworkId} 0 2 0x00EE {11 00 01 01} {0xC216}")
	sendHubCommand(new hubitat.device.HubMultiAction(stateRequest, hubitat.device.Protocol.ZIGBEE))
	logging("${device} : Refreshed",100)

}


def rangeAndRefresh() {

	// This is a ranging report and refresh call.
	rangingMode()
	runIn(3,"${state.operatingMode}Mode")

}


def parse(String description) {
		
	def descriptionMap = zigbee.parseDescriptionAsMap(description)
	
	if (descriptionMap) {
	
		logging("${device} : Splurge!: ${descriptionMap}",109)
		outputValues(descriptionMap)

	} else {
		
		logging("${device} : PARSE FAILED : $description",101)

	}	

}


def outputValues(map) {

	String[] receivedData = map.data

	if (map.clusterId == "00EF") {

		// Cluster 00EF deals with power usage information.

		if (map.command == "81") {

			// Command 81 returns immediate power readings.

			def powerValueHex = "undefined"
			def int powerValue = 0

			powerValueHex = receivedData[0..1].reverse().join()
			logging("${device} : power byte flipped : ${powerValueHex}",109)
			powerValue = zigbee.convertHexToInt(powerValueHex)
			logging("${device} : power sensor reports : ${powerValue}",109)

			logging("${device} : Power Reading : ${powerValue} W",100)

			sendEvent(name: "power", value: powerValue, unit: "W", isStateChange: true)
			sendEvent(name: "powerWithUnit", value: "${powerValue} W", isStateChange: true)

		} else if (map.command == "82") {

			// Command 82 returns usage summary in watt-hours with an uptime counter.

			def usageValueHex = "undefined"
			def int usageValue = 0 

			usageValueHex = receivedData[0..3].reverse().join()
			logging("${device} : usage byte flipped : ${usageValueHex}",109)
			usageValue = zigbee.convertHexToInt(usageValueHex)
			logging("${device} : usage counter reports : ${usageValue}",109)

			usageValue = usageValue / 3600

			logging("${device} : Power Usage : ${usageValue} Wh",100)

			sendEvent(name:"usage", value: usageValue, unit: "Wh", isStateChange: false)
			sendEvent(name:"usageWithUnit", value: "${usageValue} Wh", isStateChange: false)

			def uptimeValueHex = "undefined"
			def int uptimeValue = 0

			uptimeValueHex = receivedData[4..8].reverse().join()
			logging("${device} : uptime byte flipped : ${uptimeValueHex}",109)
			uptimeValue = zigbee.convertHexToInt(uptimeValueHex)
			logging("${device} : uptime counter reports : ${uptimeValue}",109)

			logging("${device} : Uptime : ${uptimeValue} s",100)

			sendEvent(name:"uptime", value: uptimeValue, unit: "s", isStateChange: false)

		} else {

			// Unknown power usage data.
			reportToDev(receivedData,map)

		}

	} else if (map.clusterId == "00F0") {

		// Cluster 00F0 deals with device status, including battery and temperature data.

		// Report the battery voltage and calculated percentage.
		def batteryVoltageHex = "undefined"
		def float batteryVoltage = 0
		batteryVoltageHex = receivedData[5..6].reverse().join()
		logging("${device} : batteryVoltageHex byte flipped : ${batteryVoltageHex}",109)
		batteryVoltage = zigbee.convertHexToInt(batteryVoltageHex) / 1000
		sendEvent(name:"batteryVoltage", value: batteryVoltage, unit: "V", isStateChange: false)
		sendEvent(name:"batteryVoltageWithUnit", value: "${batteryVoltage} V", isStateChange: false)
		parseAndSendBatteryStatus(batteryVoltage)

		// Report the temperature in celsius.
		def temperatureValue = "undefined"
		temperatureValue = receivedData[7..8].reverse().join()
		logging("${device} : temperatureValue byte flipped : ${temperatureValue}",109)
		temperatureValue = zigbee.convertHexToInt(temperatureValue) / 16
		logging("${device} : Temperature : ${temperatureValue} C",100)
		sendEvent(name:"temperature", value: temperatureValue, unit: "C", isStateChange: false)
		sendEvent(name:"temperatureWithUnit", value: "${temperatureValue} °C", unit: "C", isStateChange: false)

	} else if (map.clusterId == "00F2") {

		// Tamper status, not normally received from smart plugs.
		reportToDev(receivedData,map)

	} else if (map.clusterId == "00F3") {

		// State change, not normally received from smart plugs.
		reportToDev(receivedData,map)

	} else if (map.clusterId == "00F6") {

		if (map.command == "FD") {

			def rssiRangingHex = "undefined"
			def int rssiRanging = 0
			rssiRangingHex = receivedData[0]
			rssiRanging = zigbee.convertHexToInt(rssiRangingHex)
			sendEvent(name:"rssi", value: rssiRanging, isStateChange: false)
			logging("${device} : rssiRanging : ${rssiRanging}",109)

			if (receivedData[1] == "FF") {
				// This is a general ranging report, trigger a refresh for good measure.
				refresh()
			} else if (receivedData[1] == "77") {
				// This is ranging mode, which must be temporary. Make sure we come out of it.
				state.rangingPulses++
				if (state.rangingPulses > 30) {
					"${state.operatingMode}Mode"()
				}
			}

		} else {

			logging("${device} : Receiving a message on the join cluster. This device probably wants us to ask how it's feeling.",101)
			logging("${device} : Received clusterId ${map.clusterId} command ${map.command} with ${receivedData.length} values: ${receivedData}",101)
			refresh()

		}

	} else {

		// Not a clue what we've received.
		reportToDev(receivedData,map)

	}

	return null

}


void parseAndSendBatteryStatus(BigDecimal vCurrent) {

	BigDecimal bat = 0
	BigDecimal vMin = batteryVoltageMinimum == null ? 2.5 : batteryVoltageMinimum
	BigDecimal vMax = batteryVoltageMaximum == null ? 3.0 : batteryVoltageMaximum    

	if(vMax - vMin > 0) {
		bat = ((vCurrent - vMin) / (vMax - vMin)) * 100.0
	} else {
		bat = 100
	}
	bat = bat.setScale(0, BigDecimal.ROUND_HALF_UP)
	bat = bat > 100 ? 100 : bat
	
	vCurrent = vCurrent.setScale(3, BigDecimal.ROUND_HALF_UP)

	logging("${device} : Battery : $bat% ($vCurrent V)", 100)
	sendEvent(name:"battery",value:bat,unit: "%", isStateChange: false)
	sendEvent(name:"batteryWithUnit",value:"${bat} %",isStateChange: false)

}


private boolean logging(message, level) {

	boolean didLog = false

	// Critical warnings are always allowed.
	if (level == 201) {
		log.warn "$message"
		didLog = true
	}

	if (!silenceLogging) {

		// Standard logging will obey the log preferences, except for warnings, which are allowed.
		if (level == 101) {
			log.warn "$message"
			didLog = true
		} else if (level == 100 || level == 109) {
			if (level == 100) {
				if (infoLogging) {
					log.info "$message"
					didLog = true
				}
			} else {
				if (debugLogging) {
					log.debug "$message"
					didLog = true
				}
			}
		}

		// Critical logging for non-repeating events will be allowed through.
		if (level == 200 || level == 209) {
			if (level == 200) {
				log.info "$message"
				didLog = true
			} else {
				log.debug "$message"
				didLog = true
			}
		}

	}

	return didLog

}
