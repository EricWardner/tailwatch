package com.example.thing

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Location
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.runtime.livedata.observeAsState
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.ActivityResultLauncher
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.*

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.lifecycle.LiveData
import androidx.room.*
import com.fonfon.kgeohash.GeoHash
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationServices

import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.Priority
import com.google.android.gms.tasks.CancellationTokenSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.Date
import androidx.compose.foundation.lazy.items


class MainActivity : AppCompatActivity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private lateinit var locationHelper: LocationHelper

    private val requiredPermissions = arrayOf(
        Manifest.permission.BLUETOOTH,
        Manifest.permission.BLUETOOTH_ADMIN,
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
        Manifest.permission.ACCESS_FINE_LOCATION,
        Manifest.permission.ACCESS_COARSE_LOCATION
    )

    val macMap = mutableMapOf<String, MutableMap<GeoHash, Int>>()


    private val requestMultiplePermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allPermissionsGranted = permissions.entries.all { it.value }
        if (allPermissionsGranted) {
            initBluetooth()
        } else {
            showToast("All required permissions must be granted to scan for Bluetooth devices.")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        locationHelper = LocationHelper(this)
        initializeBluetoothAdapter()
        checkPermissionsAndStartBluetoothOperations()
    }

    private fun initializeBluetoothAdapter() {
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
        bluetoothAdapter = bluetoothManager.adapter
        if (bluetoothAdapter == null) {
            // Handle the case where device does not support Bluetooth
            // For example, show a Toast or disable Bluetooth features in your app
            showToast("This device doesn't support Bluetooth")
        }
    }

    private fun checkPermissionsAndStartBluetoothOperations() {
        if (arePermissionsGranted()) {
            initBluetooth()
        } else {
            requestMultiplePermissionsLauncher.launch(requiredPermissions)
        }
    }

    private fun arePermissionsGranted() = requiredPermissions.all { permission ->
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED
    }


    private fun initBluetooth() {
        Log.i("initBluetooth", "Starting")

        if (bluetoothAdapter?.isEnabled == true) {
            registerBluetoothReceiver()
            Log.i("initBluetooth", "Starting discovery")
            bluetoothAdapter?.startDiscovery()
        } else {
            // Prompt user to enable Bluetooth
            promptEnableBluetooth()
        }
    }

    private fun registerBluetoothReceiver() {
        Log.i("registerBluetoothReceiver", "Starting")

        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND).apply {
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        }
        registerReceiver(bluetoothReceiver, filter)
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                BluetoothDevice.ACTION_FOUND -> {
                    var locationMap = mutableMapOf<GeoHash, Int>()
                    val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)

                    locationHelper.getCurrentLocation()
                    val location = locationHelper.loc
                    if (location != null && device != null) {

                        val newDevice = Device(macAddress = device.address)
                        val geoHashLocation = GeoHash(location,11)
                        val detection = Detection(
                            deviceMacAddress = device.address,
                            location = geoHashLocation.toString(),
                            time = Date()
                        )

                        (application as MacDeviceApplication).repository.apply {
                            CoroutineScope(Dispatchers.IO).launch {
                                insertDeviceAndDetection(newDevice, detection)
                            }
                        }


                        Log.i("Location", geoHashLocation.toString())
                        locationMap[geoHashLocation] = locationMap.getOrPut(geoHashLocation) { 0 } + 1
                        val deviceMAC = device?.address // MAC address
                        if (!deviceMAC.isNullOrEmpty()) {
                            macMap[deviceMAC] = locationMap
                            Log.i("SIZE", "${macMap.size}")
                            Log.i(
                                "BLUETOOTH",
                                macMap.map { "${it.key}: ${it.value}" }.joinToString(", ")
                            )

                            setContent {
                                val multipleLocationsDevices = (application as MacDeviceApplication).repository.devicesMultipleLocations
                                BluetoothDeviceList(multipleLocationsDevices)
                            }
                        }
                    }
                }
                BluetoothAdapter.ACTION_DISCOVERY_STARTED -> {
                    // Update UI to show that the discovery has started
                    Log.i("bluetoothReceiver", "Discovery started")
                }
                BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> {
                    // Update UI to show that the discovery has finished
                    Log.i("bluetoothReceiver", "Discovery completed")

                    initBluetooth()
                }
            }
        }
    }

    private fun promptEnableBluetooth() {
        val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
        startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
    }

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver was probably already stopped or not registered
        }
    }

    companion object {
        const val REQUEST_ENABLE_BT = 1
    }
}


class LocationHelper(private val context: Context) {
    private val fusedLocationClient: FusedLocationProviderClient = LocationServices.getFusedLocationProviderClient(context)
    private val cancellationTokenSource = CancellationTokenSource()
    var loc: Location? = null

    fun getCurrentLocation() {
        // Check for location permission

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) {
            fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, cancellationTokenSource.token)
                .addOnSuccessListener { location: Location? ->
                    // Handle the location object
                    if (location != null) {
                        handleLocation(location)
                    } else {
                        handleNullLocation()
                    }
                }
                .addOnFailureListener { exception ->
                    // Handle possible errors
                    handleError(exception)
                }
        } else {
            // Handle the case where permission is not granted
            handlePermissionNotGranted()
        }
    }

    private fun handleLocation(location: Location) {
        println("Latitude: ${location.latitude}, Longitude: ${location.longitude}")
        loc = location
    }

    private fun handleNullLocation() {
        println("No location available.")
    }

    private fun handleError(exception: Exception) {
        println("Error retrieving location: ${exception.localizedMessage}")
    }

    private fun handlePermissionNotGranted() {
        println("Permission not granted.")
    }
}


@Composable
fun BluetoothDeviceList(devices: LiveData<List<DeviceDetectionCount>>) {
    val deviceList by devices.observeAsState(initial = emptyList())

    LazyColumn {
            items(deviceList) {device ->
                ListItem(
                    headlineContent = { Text("Device: ${device.macAddress}, Locations Detected: ${device.count}") },
                    supportingContent = {Text("Locations: ${device.locations}")                 }
                )
                HorizontalDivider()
            }
    }
}


@Entity(tableName = "devices")
data class Device(
    @PrimaryKey @ColumnInfo(name = "mac_address") val macAddress: String
)

@Entity(tableName = "detections",
    foreignKeys = [ForeignKey(entity = Device::class,
        parentColumns = arrayOf("mac_address"),
        childColumns = arrayOf("device_mac_address"),
        onDelete = ForeignKey.CASCADE)],
    indices = [Index(value = ["device_mac_address"])])
data class Detection(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    @ColumnInfo(name = "device_mac_address") val deviceMacAddress: String,
    @ColumnInfo(name = "location") val location: String,
    @ColumnInfo(name = "time") val time: Date
)

data class DeviceDetectionCount(
    @ColumnInfo(name = "device_mac_address") val macAddress: String,
    @ColumnInfo(name = "location_count") val count: Int,
    @ColumnInfo(name = "locations") val locations: String
)

@Dao
interface DeviceDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    fun insertDevice(device: Device): Long

    @Insert
    fun insertDetection(detection: Detection)

    @Query("SELECT * FROM devices ORDER BY mac_address ASC")
    fun getAllDevices(): LiveData<List<Device>>

    @Query("SELECT * FROM detections WHERE device_mac_address = :macAddress")
    fun getDetectionsForDevice(macAddress: String): List<Detection>

    @Query("""
    SELECT device_mac_address, COUNT(DISTINCT location) AS location_count, 
           GROUP_CONCAT(DISTINCT location) AS locations
    FROM detections 
    GROUP BY device_mac_address 
    HAVING location_count > 1 
    ORDER BY location_count DESC
    """)
    fun getDevicesDetectedInMultipleLocations(): LiveData<List<DeviceDetectionCount>>

}

@Database(entities = [Device::class, Detection::class], version = 1)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun deviceDao(): DeviceDao

    companion object {
        @Volatile
        private var INSTANCE: AppDatabase? = null

        fun getDatabase(context: Context): AppDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    AppDatabase::class.java,
                    "app_database"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}

class MacRepository(private val deviceDao: DeviceDao) {
    // LiveData or a normal list, depending on your use case
    val allDevices: LiveData<List<Device>> = deviceDao.getAllDevices()
    val devicesMultipleLocations: LiveData<List<DeviceDetectionCount>> = deviceDao.getDevicesDetectedInMultipleLocations()

    suspend fun insertDeviceAndDetection(device: Device, detection: Detection) {
        deviceDao.insertDevice(device)
        deviceDao.insertDetection(detection)
    }

}

class MacDeviceApplication : Application() {
    val database by lazy { AppDatabase.getDatabase(this) }
    val repository by lazy { MacRepository(database.deviceDao()) }
}

class Converters {
    @TypeConverter
    fun fromDate(date: Date?): Long? {
        return date?.time
    }

    @TypeConverter
    fun toDate(timestamp: Long?): Date? {
        return timestamp?.let { Date(it) }
    }
}