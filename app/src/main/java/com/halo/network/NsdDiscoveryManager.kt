package com.halo.network

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.InetAddress

data class DiscoveredDevice(
    val name: String,
    val host: InetAddress,
    val port: Int
)

class NsdDiscoveryManager(context: Context) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val serviceType = "_halo._tcp."
    private var serviceName = "HALO_Device"

    private val _discoveredDevices = MutableStateFlow<List<DiscoveredDevice>>(emptyList())
    val discoveredDevices: StateFlow<List<DiscoveredDevice>> = _discoveredDevices.asStateFlow()

    private var registrationListener: NsdManager.RegistrationListener? = null
    private var discoveryListener: NsdManager.DiscoveryListener? = null

    // Helper map to keep track of discovered services before they are fully resolved
    private val pendingServices = mutableMapOf<String, NsdServiceInfo>()

    fun registerService(port: Int) {
        val serviceInfo = NsdServiceInfo().apply {
            serviceName = "HALO_${android.os.Build.MODEL}"
            serviceType = this@NsdDiscoveryManager.serviceType
            this.port = port
        }

        registrationListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(NsdServiceInfo: NsdServiceInfo) {
                serviceName = NsdServiceInfo.serviceName
                Log.d("NSD", "Service registered: $serviceName")
            }

            override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Registration failed! Error code: $errorCode")
            }

            override fun onServiceUnregistered(arg0: NsdServiceInfo) {
                Log.d("NSD", "Service unregistered: ${arg0.serviceName}")
            }

            override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                Log.e("NSD", "Unregistration failed! Error code: $errorCode")
            }
        }

        try {
            nsdManager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, registrationListener)
        } catch (e: Exception) {
            Log.e("NSD", "Failed to register NSD service", e)
        }
    }

    fun unregisterService() {
        try {
            registrationListener?.let { nsdManager.unregisterService(it) }
        } catch (e: Exception) {
            Log.e("NSD", "Failed to unregister NSD service", e)
        } finally {
            registrationListener = null
        }
    }

    fun startDiscovery() {
        stopDiscovery() // Ensure no previous listener is active
        _discoveredDevices.value = emptyList()

        discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(regType: String) {
                Log.d("NSD", "Service discovery started")
            }

            override fun onServiceFound(service: NsdServiceInfo) {
                Log.d("NSD", "Service discovery success: $service")
                if (service.serviceType == serviceType) {
                    if (service.serviceName == serviceName) {
                        Log.d("NSD", "Same machine: ${service.serviceName}")
                    } else {
                        // Resolve the service to get IP and port
                        resolveService(service)
                    }
                }
            }

            override fun onServiceLost(service: NsdServiceInfo) {
                Log.e("NSD", "Service lost: $service")
                val updatedList = _discoveredDevices.value.filter { it.name != service.serviceName }
                _discoveredDevices.value = updatedList
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.i("NSD", "Discovery stopped: $serviceType")
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery failed: Error code:$errorCode")
                stopDiscovery()
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e("NSD", "Discovery failed: Error code:$errorCode")
                stopDiscovery()
            }
        }

        try {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
        } catch (e: Exception) {
            Log.e("NSD", "Failed to start discovery", e)
        }
    }

    private fun resolveService(service: NsdServiceInfo) {
        try {
            nsdManager.resolveService(service, object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                    Log.e("NSD", "Resolve failed: $errorCode")
                }

                override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                    Log.e("NSD", "Resolve Succeeded. ${serviceInfo}")
                    val host: InetAddress = serviceInfo.host
                    val port: Int = serviceInfo.port
                    
                    val device = DiscoveredDevice(serviceInfo.serviceName, host, port)
                    val currentList = _discoveredDevices.value.toMutableList()
                    
                    // Update or add the device
                    val index = currentList.indexOfFirst { it.name == device.name }
                    if (index != -1) {
                        currentList[index] = device
                    } else {
                        currentList.add(device)
                    }
                    _discoveredDevices.value = currentList
                }
            })
        } catch (e: Exception) {
            Log.e("NSD", "Failed to resolve service", e)
        }
    }

    fun stopDiscovery() {
        try {
            discoveryListener?.let { nsdManager.stopServiceDiscovery(it) }
        } catch (e: Exception) {
            Log.e("NSD", "Failed to stop discovery", e)
        } finally {
            discoveryListener = null
        }
    }
}
