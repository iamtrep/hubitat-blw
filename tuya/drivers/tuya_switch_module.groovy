/*
 * 
 *  Tuya Switch Module TS0001 / TS0011 / TS0012 Driver
 *	
 */


@Field String driverVersion = "v1.06 (29th August 2023)"
@Field boolean debugMode = false


#include BirdsLikeWires.library
import groovy.transform.Field

@Field String deviceMan = "Tuya"
@Field String deviceType = "Switch Module"

@Field int reportIntervalMinutes = 10
@Field int checkEveryMinutes = 4


metadata {

	definition (name: "$deviceMan $deviceType", namespace: "BirdsLikeWires", author: "Andrew Davison", importUrl: "https://raw.githubusercontent.com/birdslikewires/hubitat/master/tuya/drivers/tuya_switch_module_ts0011_ts0012.groovy") {

		capability "Actuator"
		capability "Configuration"
		capability "HealthCheck"
		capability "Refresh"
		capability "Switch"

		command "flash"

		attribute "healthStatus", "enum", ["offline", "online"]
		attribute "mode", "string"

		if (debugMode) {
			command "testCommand"
		}

		fingerprint profileId: "0104", inClusters: "0000,0003,0006", outClusters: "0003,0006,0004", manufacturer: "_TYZB01_phjeraqq", model: "TS0001", deviceJoinName: "$deviceMan $deviceType TS0001", application: "43"
		fingerprint profileId: "0104", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", manufacturer: "_TZ3000_qmi1cfuq", model: "TS0011", deviceJoinName: "$deviceMan $deviceType TS0011", application: "43"
		fingerprint profileId: "0104", inClusters: "0003,0004,0005,0006,E000,E001,0000", outClusters: "0019,000A", manufacturer: "_TZ3000_jl7qyupf", model: "TS0012", deviceJoinName: "$deviceMan $deviceType TS0012", application: "43"

	}

}


preferences {
	
	input name: "flashEnabled", type: "bool", title: "Enable flash", defaultValue: false
	input name: "flashRate", type: "number", title: "Flash rate (ms)", range: "500..5000", defaultValue: 1000

	if ("${getDeviceDataByName('model')}" == "TS0012") {
		input name: "flashRelays", type: "enum", title: "Flash relay", options:[["FF":"Both"],["01":"Relay 1"],["02":"Relay 2"]]
	}

	input name: "infoLogging", type: "bool", title: "Enable logging", defaultValue: true
	input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: true
	input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: true	

}


void testCommand() {

	logging("${device} : Test Command", "info")

}


void configureSpecifics() {
	// Called by main configure() method in BirdsLikeWires.library

	requestBasic()

	// Set device name.
	String deviceModel = getDeviceDataByName('model')
	device.name = "$deviceMan $deviceType $deviceModel"

	// Store relay count and create children.
	state.relayCount = ("${getDeviceDataByName('model')}" == "TS0012") ? 2 : 1
	if (state.relayCount > 1) {
		for (int i = 1; i == state.relayCount; i++) {
			fetchChild("hubitat","Switch","0$i")
		}
	} else {
		deleteChildren()
	}

	// Always set to 'static' to ensure we're never stuck in 'flashing' mode.
	sendEvent(name: "mode", value: "static", isStateChange: false)

	// Reporting

	/// Sadly it would appear that these modules don't support binding to 00 or FF as an endpoint.
	/// On other modules it's possible to do a configureReporting((0x0006, 0x0000...) which results in all
	/// endpoint states being reported. Instead we just have to poll, ironically using FF as the endpoint.

	sendZigbeeCommands(zigbee.onOffConfig())
	randomSixty = Math.abs(new Random().nextInt() % 60)
	schedule("${randomSixty} 0/${reportIntervalMinutes} * * * ? *", refresh)

}


void updateSpecifics() {
	// Called by updated() method in BirdsLikeWires.library

	return

}


void ping() {

	logging("${device} : Ping", "info")
	refresh()

}


void refresh() {

	sendZigbeeCommands(["he rattr 0x${device.deviceNetworkId} 0xFF 0x0006 0x00 {}"])
	logging("${device} : Refreshed", "info")

}


void off() {

	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x00 {}"])
	sendEvent(name: "mode", value: "static")

}


void on() {

	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0xFF 0x0006 0x01 {}"])
	sendEvent(name: "mode", value: "static")

}


void flash() {

	if (!flashEnabled) {
		logging("${device} : Flash : Disabled", "warn")
		return
	}

	if (!flashRelays && "${getDeviceDataByName('model')}" == "TS0012") {
		logging("${device} : Flash : No relay chosen in preferences.", "warn")
		return
	}

	logging("${device} : Flash : Rate of ${flashRate ?: 1000} ms", "info")
	sendEvent(name: "mode", value: "flashing")
	pauseExecution 200
    flashOn()

}


void flashOn() {

	String mode = device.currentState("mode").value
	logging("${device} : flashOn : Mode is ${mode}", "debug")

    if (mode != "flashing") return
    runInMillis((flashRate ?: 1000).toInteger(), flashOff)

	String flashEndpoint = "FF"
	if (flashRelays) flashEndpoint = flashRelays

	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${flashEndpoint} 0x0006 0x01 {}"])

}


void flashOff() {

	String mode = device.currentState("mode").value
	logging("${device} : flashOn : Mode is ${mode}", "debug")

    if (mode != "flashing") return
	runInMillis((flashRate ?: 1000).toInteger(), flashOn)

	String flashEndpoint = "FF"
	if (flashRelays) flashEndpoint = flashRelays

	sendZigbeeCommands(["he cmd 0x${device.deviceNetworkId} 0x${flashEndpoint} 0x0006 0x00 {}"])

}


void parse(String description) {

	updateHealthStatus()
	checkDriver()

	Map descriptionMap = zigbee.parseDescriptionAsMap(description)

	if (descriptionMap) {

		logging("${device} : Parse : ${descriptionMap}", "debug")
		processMap(descriptionMap)

	} else {
		
		logging("${device} : Parse : Failed to parse received data. Please report these messages to the developer.", "warn")
		logging("${device} : Parse : ${description}", "error")

	}

}


void processMap(Map map) {

	logging("${device} : processMap() : ${map}", "trace")

	if (map.cluster == "0001") {
		// Power configuration.

		if (map.value) {

			logging("${device} : Switch ${map.endpoint} : On", "warn")

		} else {

			filterThis(map)

		}

	} else if (map.cluster == "0006" || map.clusterId == "0006") {
		// Relay configuration and response handling.
		// State confirmation and command receipts.

		if (map.command == "01") {
			// Relay States (Refresh)

			if (map.value == "00") {

				if (state.relayCount > 1) {

					def childDevice = fetchChild("hubitat", "Switch", "${map.endpoint}")
					childDevice.parse([[name:"switch", value:"off"]])
					def currentChildStates = fetchChildStates("switch","${childDevice.id}")

					if (currentChildStates.every{it == "off"}) {
						logging("${device} : Switch : All Off", "info")
						sendEvent(name: "switch", value: "off")
					}

				} else {

					sendEvent(name: "switch", value: "off")

				}

				logging("${device} : Switch ${map.endpoint} : Off", "info")

			} else {

				if (state.relayCount > 1) {
					def childDevice = fetchChild("hubitat", "Switch", "${map.endpoint}")
					childDevice.parse([[name:"switch", value:"on"]])
				}

				sendEvent(name: "switch", value: "on")
				logging("${device} : Switch ${map.endpoint} : On", "info")

			}

		} else if (map.command == "07") {
			// Relay Configuration

			logging("${device} : Relay Configuration : Successful", "info")

		} else if (map.command == "0A" || map.command == "0B") {
			// Relay States

			// Command "0A" is local actuation, command "0B" is remote actuation.
			String relayActuation = (map.command == "0A") ? "local" : "remote"
			
			// These appear to always report on both 0A and 0B when remote controlled, so I'm skipping the local message for now.

			// I'll investigate this with a momentary switch when I get chance.

			// Temporary Skipper
			if (map.command == "0A") {
				logging("${device} : Skipping $relayActuation actuation message", "debug")
				return
			}

			String relayActuated = (map.command == "0A") ? map.endpoint : map.sourceEndpoint
			String relayState = (map.command == "0A") ? map.value : map.data[0]
			String relayOnOff = (relayState == "00") ? "off" : "on"

			if (state.relayCount > 1) {

				def childDevice = fetchChild("hubitat", "Switch", "$relayActuated")
				childDevice.parse([[name:"switch", value:"${relayOnOff}"]])
				def currentChildStates = fetchChildStates("switch","${childDevice.id}")

				// You need all of them off to be off, but only one to be on to be on. ;)
				if (relayOnOff == "off" && currentChildStates.every{it == "off"}) {

					debounceParentState("switch", "${relayOnOff}", "All Devices ${relayOnOff.capitalize()}", "info", 300)
					sendEvent(name: "switch", value: "${relayOnOff}")

				} else if (relayOnOff == "on") {

					sendEvent(name: "switch", value: "${relayOnOff}")

				}

			} else {

				sendEvent(name: "switch", value: "${relayOnOff}")

			}

			logging("${device} : ${relayActuation.capitalize()}ly Switched $relayActuated : ${relayOnOff.capitalize()}", "info")

		} else if (map.command == "00") {

			logging("${device} : Skipped : State Counter Message", "debug")

		} else {

			filterThis(map)

		}

	} else {

		filterThis(map)

	}
	
}
