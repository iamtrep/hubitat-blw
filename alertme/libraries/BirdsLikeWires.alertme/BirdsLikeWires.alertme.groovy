/*
 * 
 *  BirdsLikeWires AlertMe Library v1.00 (27th June 2022)
 *	
 */


import hubitat.zigbee.clusters.iaszone.ZoneStatus


library (
	author: "Andrew Davison",
	category: "zigbee",
	description: "Library methods used by BirdsLikeWires AlertMe drivers.",
	documentationLink: "https://github.com/birdslikewires/hubitat",
	name: "alertme",
	namespace: "BirdsLikeWires"
)


void installed() {
	// Runs after first installation.
	logging("${device} : Installed", "info")
	configure()
}


void configure() {

	int randomSixty

	// Tidy up.
	unschedule()
	state.clear()
	state.operatingMode = "normal"
	state.presenceUpdated = 0
	sendEvent(name: "presence", value: "present", isStateChange: false)

	// Schedule presence checking.
	randomSixty = Math.abs(new Random().nextInt() % 60)
	schedule("${randomSixty} 0/${checkEveryMinutes} * * * ? *", checkPresence)

	// Schedule ranging report.
	randomSixty = Math.abs(new Random().nextInt() % 60)
	randomTwentyFour = Math.abs(new Random().nextInt() % 24)
	schedule("${randomSixty} ${randomSixty} ${randomTwentyFour}/${rangeEveryHours} * * ? *", rangingMode)

	// Set device specifics.
	configureSpecifics()

	// Notify.
	sendEvent(name: "configuration", value: "complete", isStateChange: false)
	logging("${device} : Configuration complete.", "info")

	updated()
	rangingMode()
	
}


void updated() {
	// Runs when preferences are saved.

	unschedule(infoLogOff)
	unschedule(debugLogOff)
	unschedule(traceLogOff)

	if (!debugMode) {
		runIn(2400,debugLogOff)
		runIn(1200,traceLogOff)
	}

	logging("${device} : Preferences Updated", "info")

	loggingStatus()

}


void normalMode() {
	// Normal operation.

	state.operatingMode = "normal"
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00F0 {11 00 FA 00 01} {0xC216}"])
	logging("${device} : Operation : Normal", "info")

}


void rangingMode() {
	// Ranging mode double-flashes (good signal) or triple-flashes (poor signal) the indicator while reporting LQI values.

	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00F0 {11 00 FA 01 01} {0xC216}"])
	logging("${device} : Operation : Ranging", "info")

	// Ranging will be disabled after a maximum of 30 pulses.
	state.rangingPulses = 0

}


void quietMode() {
	// Turns off all reporting except for a ranging message every 2 minutes. Pretty useless except as a temporary state.

	state.operatingMode = "quiet"
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00F0 {11 00 FA 03 01} {0xC216}"])
	logging("${device} : Operation : Quiet", "info")

}


void refresh() {

	logging("${device} : Refreshing", "info")
	sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x00F6 {11 00 FC 01} {0xC216}"])	   // version information request

}


void parse(String description) {

	logging("${device} : Parse : $description", "debug")

	updatePresence()

	if (description.startsWith("zone status")) {

		ZoneStatus zoneStatus = zigbee.parseZoneStatus(description)
		processStatus(zoneStatus)

	} else if (description?.startsWith('enroll request')) {
				
		logging("${device} : Parse : Enrol request received, sending response.", "debug")
		sendZigbeeCommands(["he raw ${device.deviceNetworkId} 0 ${device.endpointId} 0x0500 {11 80 00 00 05} {0xC216}"])

	} else {

		Map descriptionMap = zigbee.parseDescriptionAsMap(description)

		if (descriptionMap) {

			processMap(descriptionMap)

		} else {
			
			logging("${device} : Parse : Failed to parse received data. Please report these messages to the developer.", "warn")
			logging("${device} : Splurge! : ${description}", "warn")

		}

	}

}


void alertmeDeviceStatus(Map map) {

	// 00F0 - Device Status Cluster

	String modelCheck = "${getDeviceDataByName('model')}"

	// Report the battery voltage and calculated percentage.
	def batteryVoltageHex = "undefined"
	BigDecimal batteryVoltage = 0

	batteryVoltageHex = map.data[5..6].reverse().join()
	logging("${device} : batteryVoltageHex byte flipped : ${batteryVoltageHex}", "trace")

	if (batteryVoltageHex == "FFFF") {
		// Occasionally a weird battery reading can be received. Ignore it.
		logging("${device} : batteryVoltageHex skipping anomolous reading : ${batteryVoltageHex}", "debug")
		return
	}

	batteryVoltage = zigbee.convertHexToInt(batteryVoltageHex) / 1000
	logging("${device} : batteryVoltage sensor value : ${batteryVoltage}", "debug")

	batteryVoltage = batteryVoltage.setScale(3, BigDecimal.ROUND_HALF_UP)

	logging("${device} : batteryVoltage : ${batteryVoltage}", "debug")
	sendEvent(name: "batteryVoltage", value: batteryVoltage, unit: "V")

	BigDecimal batteryPercentage = 0
	BigDecimal batteryVoltageScaleMin = 2.8
	BigDecimal batteryVoltageScaleMax = 3.1

	if (batteryVoltage >= batteryVoltageScaleMin && batteryVoltage <= 4.4) {

		batteryPercentage = ((batteryVoltage - batteryVoltageScaleMin) / (batteryVoltageScaleMax - batteryVoltageScaleMin)) * 100.0
		batteryPercentage = batteryPercentage.setScale(0, BigDecimal.ROUND_HALF_UP)
		batteryPercentage = batteryPercentage > 100 ? 100 : batteryPercentage

		if (batteryPercentage > 20) {
			logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "info")
		} else {
			logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "warn")
		}

		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		sendEvent(name: "batteryState", value: "discharging")

	} else if (batteryVoltage < batteryVoltageScaleMin) {

		// Very low voltages indicate an exhausted battery which requires replacement.

		batteryPercentage = 0

		logging("${device} : Battery : Exhausted battery requires replacement.", "warn")
		logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "warn")
		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		sendEvent(name: "batteryState", value: "exhausted")

	} else {

		// If the charge circuitry is reporting greater than 4.5 V then the battery is either missing or faulty.
		// THIS NEEDS TESTING ON THE EARLY POWER CLAMP

		batteryPercentage = 0

		logging("${device} : Battery : Exhausted battery requires replacement.", "warn")
		logging("${device} : Battery : $batteryPercentage% ($batteryVoltage V)", "warn")
		sendEvent(name: "battery", value:batteryPercentage, unit: "%")
		sendEvent(name: "batteryState", value: "fault")

	}

	if ("$modelCheck" != "Care Pendant Device" && "$modelCheck" != "Keyfob Device") {

		// Report the temperature in celsius.
		def temperatureValue = "undefined"
		temperatureValue = map.data[7..8].reverse().join()
		logging("${device} : temperatureValue byte flipped : ${temperatureValue}", "trace")
		BigDecimal temperatureCelsius = hexToBigDecimal(temperatureValue) / 16

		logging("${device} : temperatureCelsius sensor value : ${temperatureCelsius}", "trace")
		logging("${device} : Temperature : $temperatureCelsius°C", "info")
		sendEvent(name: "temperature", value: temperatureCelsius, unit: "C")

	}

}


void alertmeDiscovery(Map map) {

	// 00F6 - Discovery Cluster

	if (map.command == "FD") {

		// Ranging is our jam, Hubitat deals with joining on our behalf.

		def lqiRangingHex = "undefined"
		int lqiRanging = 0
		lqiRangingHex = map.data[0]
		lqiRanging = zigbee.convertHexToInt(lqiRangingHex)
		sendEvent(name: "lqi", value: lqiRanging)
		logging("${device} : lqiRanging : ${lqiRanging}", "debug")

		if (map.data[1] == "77") {

			// This is ranging mode, which must be temporary. Make sure we come out of it.
			state.rangingPulses++
			if (state.rangingPulses > 10) {
				"${state.operatingMode}Mode"()
			}

		} else if (map.data[1] == "FF") {

			// This is the ranging report received every 30 seconds while in quiet mode.
			logging("${device} : quiet ranging report received", "debug")

		} else if (map.data[1] == "00") {

			// This is the ranging report received when the device reboots.
			// After rebooting a refresh is required to bring back remote control.
			logging("${device} : reboot ranging report received", "debug")
			refresh()

		} else {

			reportToDev(map)

		} 

	} else if (map.command == "FE") {

		// Device version response.

		def versionInfoHex = map.data[31..map.data.size() - 1].join()

		StringBuilder str = new StringBuilder()
		for (int i = 0; i < versionInfoHex.length(); i+=2) {
			str.append((char) Integer.parseInt(versionInfoHex.substring(i, i + 2), 16))
		} 

		String versionInfo = str.toString()
		String[] versionInfoBlocks = versionInfo.split("\\s")
		int versionInfoBlockCount = versionInfoBlocks.size()
		String versionInfoDump = versionInfoBlocks[0..versionInfoBlockCount - 1].toString()

		logging("${device} : device version received in ${versionInfoBlockCount} blocks : ${versionInfoDump}", "debug")

		String deviceManufacturer = "AlertMe"
		String deviceModel = ""
		String deviceFirmware = versionInfoBlocks[versionInfoBlockCount - 1]

		// Sometimes the model name contains spaces.
		if (versionInfoBlockCount == 2) {
			deviceModel = versionInfoBlocks[0]
		} else {
			deviceModel = versionInfoBlocks[0..versionInfoBlockCount - 2].join(' ').toString()
		}

		logging("${device} : Device : ${deviceModel}", "info")
		logging("${device} : Firmware : ${deviceFirmware}", "info")

		updateDataValue("manufacturer", deviceManufacturer)
		updateDataValue("model", deviceModel)
		updateDataValue("firmware", deviceFirmware)

	} else {

		reportToDev(map)

	}

}


void alertmeSkip(String clusterId) {

	// These clusters are sometimes received from the SPG100 and I have no idea why.
	//   8001 arrives with 12 bytes of data
	//   8038 arrives with 27 bytes of data
	logging("${device} : Skipping data received on clusterId ${clusterId}.", "debug")

}


void alertmeTamper(Map map) {

	// 00F2 - Tamper Cluster

	if (map.command == "00") {

		if (map.data[0] == "02") {

			logging("${device} : Tamper : Detected", "warn")
			sendEvent(name: "tamper", value: "detected")

		} else {

			reportToDev(map)

		}

	} else if (map.command == "01") {

		if (map.data[0] == "01") {

			logging("${device} : Tamper : Cleared", "info")
			sendEvent(name: "tamper", value: "clear")

		} else {

			reportToDev(map)

		}

	} else {

		reportToDev(map)

	}

}
