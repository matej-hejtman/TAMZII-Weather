package com.example.weather

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.weather.ui.theme.WeatherTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import com.github.mikephil.charting.charts.LineChart
import com.github.mikephil.charting.components.XAxis
import com.github.mikephil.charting.data.Entry
import com.github.mikephil.charting.data.LineData
import com.github.mikephil.charting.data.LineDataSet
import com.github.mikephil.charting.formatter.ValueFormatter
import org.json.JSONArray

enum class Tab(val label: String, val icon: ImageVector) {
    HOME("Domů", Icons.Default.Home),
    FORECAST("Předpověď", Icons.Default.DateRange),
    CHART("Graf", Icons.Default.Info),
    FAVORITES("Oblíbená", Icons.Default.Star)
}

const val API_KEY = "8995127544e8f1ab81935b394ced520d"
data class Weather(val temp: Double, val description: String, val icon: String)
data class ForecastItem(val time: Long, val temp: Double, val description: String, val icon: String)

fun loadFavorites(prefs: SharedPreferences): List<String> {
    val arr = JSONArray(prefs.getString("favorites", "[]"))
    return (0 until arr.length()).map {arr.getString(it)}
}

fun saveFavorites(prefs: SharedPreferences, list: List<String>) {
    prefs.edit().putString("favorites", JSONArray(list).toString()).apply()
}
fun download(endpoint: String, city: String): JSONObject {
    val q = URLEncoder.encode(city.trim(), "UTF-8")
    val url = URL("https://api.openweathermap.org/data/2.5/$endpoint?q=$q&appid=$API_KEY&units=metric&lang=cz")
    val conn = url.openConnection() as HttpURLConnection

    conn.connectTimeout = 10000
    conn.readTimeout = 10000

    try {
        when (conn.responseCode) {
            200 -> return JSONObject(conn.inputStream.bufferedReader().readText())
            401 -> throw Exception("Neplatný API klíč (nový klíč se aktivuje až po chvíli)")
            404 -> throw Exception("Město nenalezeno")

            else -> throw Exception("Chyba serveru (${conn.responseCode})")
        }
    } finally {
        conn.disconnect()
    }
}
fun parseWeather(json: JSONObject): Weather {
    val w = json.getJSONArray("weather").getJSONObject(0)
    return Weather(
        temp = json.getJSONObject("main").getDouble("temp"),
        description = w.getString("description"),
        icon = w.getString("icon")
    )
}
fun parseForecast(json: JSONObject): List<ForecastItem> {
    val list = json.getJSONArray("list")
    return (0 until list.length()).map { i ->
        val item = list.getJSONObject(i)
        val w = item.getJSONArray("weather").getJSONObject(0)
        ForecastItem(
            time = item.getLong("dt"),
            temp = item.getJSONObject("main").getDouble("temp"),
            description = w.getString("description"),
            icon = w.getString("icon")
        )
    }
}
fun errorText(e: Exception): String = if (e is IOException) "Zkontroluj připojení k internetu" else e.message ?: "Neznámá chyba"
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            WeatherTheme {
                WeatherApp()
            }
        }
    }
}
@Composable
fun WeatherApp() {
    val prefs = LocalContext.current.getSharedPreferences("weather", Context.MODE_PRIVATE)
    var tab by rememberSaveable { mutableStateOf(Tab.HOME) }
    var city by rememberSaveable { mutableStateOf(prefs.getString("city", "Ostrava") ?: "Ostrava") }
    var favorites by remember { mutableStateOf(loadFavorites(prefs)) }

    fun updateFavorites(newList: List<String>) {
        favorites = newList
        saveFavorites(prefs, newList)
    }

    fun isFavorite(c: String) = favorites.any { it.equals(c, ignoreCase = true) }

    fun toggleFavorite(c: String) {
        updateFavorites(
            if(isFavorite(c)) favorites.filterNot { it.equals(c, ignoreCase = true) }
            else favorites + c
        )
    }

    BackHandler(enabled = tab != Tab.HOME) { tab = Tab.HOME }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab == t,
                        onClick = { tab = t },
                        icon = { Icon(t.icon, contentDescription = t.label) },
                        label = { Text(t.label) }
                    )
                }
            }
        }
    ) { innerPadding ->
        val modifier = Modifier.padding(innerPadding)
        when (tab) {
            Tab.HOME -> CurrentScreen(prefs, city, onCityLoaded = { city = it }, isFavorite = ::isFavorite, onToggleFavorite = ::toggleFavorite, modifier)
            Tab.FORECAST -> ForecastScreen(city, modifier)
            Tab.CHART -> ChartScreen(city, modifier)
            Tab.FAVORITES -> FavoritesScreen(
                favorites = favorites,
                onAdd = { c -> if (!isFavorite(c)) updateFavorites(favorites + c) },
                onRemove = { c -> updateFavorites(favorites - c) },
                onSelect = { c ->
                    city = c
                    tab = Tab.HOME
                },
                modifier = modifier
            )
        }
    }
}
@Composable
fun Placeholder(text: String, modifier: Modifier) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center){
        Text(text)
    }
}
@Composable
fun CurrentScreen(prefs: SharedPreferences, city: String, onCityLoaded: (String) -> Unit, isFavorite: (String) -> Boolean, onToggleFavorite: (String) -> Unit, modifier: Modifier ) {
    var input by rememberSaveable { mutableStateOf(city) }
    var shownCity by remember { mutableStateOf("") }
    var weather by remember { mutableStateOf<Weather?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    fun refresh() {
        if (input.isBlank()) {
            error = "Zadej název města"
            return
        }
        val c = input.trim()
        scope.launch {
            loading = true
            error = null
            try {
                weather = withContext(Dispatchers.IO) { parseWeather(download("weather", c)) }
                shownCity = c
                onCityLoaded(c)
                prefs.edit().putString("city", c).apply()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                weather = null
                error = errorText(e)
            } finally {
                loading = false
            }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    Column(
        modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Aktuální počasí", fontSize = 24.sp, fontWeight = FontWeight.Bold)
        OutlinedTextField(
            value = input,
            onValueChange = { input = it },
            label = { Text("Město") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Button(onClick = { refresh() }, enabled = !loading) { Text("Aktualizovat") }

        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        weather?.let { w ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(shownCity, fontSize = 20.sp, modifier = Modifier.weight(1f))
                val fav = isFavorite(shownCity)
                IconButton(onClick = { onToggleFavorite(shownCity) }) {
                    Icon(
                        imageVector = if (fav) Icons.Default.Favorite else Icons.Outlined.FavoriteBorder,
                        contentDescription = if (fav) "Odebrat z oblíbených" else "Přidat do oblíbených",
                        tint = if (fav) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            InfoCard("Teplota") { Text("%.1f °C".format(w.temp), fontSize = 32.sp) }
            InfoCard("Popis") { Text(w.description.replaceFirstChar { it.uppercase() }, fontSize = 20.sp) }
            InfoCard("Ikona") { WeatherIcon(w.icon, 96.dp) }
        }
    }
}
@Composable
fun InfoCard(title: String, content: @Composable () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(title, style = MaterialTheme.typography.labelLarge)
            Spacer(Modifier.height(8.dp))
            content()
        }
    }
}
@Composable
fun ForecastScreen(city: String, modifier: Modifier) {
    var forecast by remember { mutableStateOf<List<ForecastItem>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var loading by remember { mutableStateOf(true) }
    val fmt = remember { SimpleDateFormat("EEE d. M. HH:mm", Locale.forLanguageTag("cs")) }

    LaunchedEffect(city) {
        try {
            forecast = withContext(Dispatchers.IO) { parseForecast(download("forecast", city)) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = errorText(e)
        } finally {
            loading = false
        }
    }

    Column(modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Předpověď: $city", fontSize = 20.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(12.dp))
        }

        Spacer(Modifier.height(12.dp))

        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(forecast) { f ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        WeatherIcon(f.icon, 48.dp)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(fmt.format(Date(f.time * 1000)), fontWeight = FontWeight.Bold)
                            Text(f.description)
                        }
                        Text("%.0f °C".format(f.temp), fontSize = 20.sp)
                    }
                }
            }
        }
    }
}

@Composable
fun WeatherIcon(code: String, size: Dp) {
    val context = LocalContext.current
    val res = context.resources
    var id = res.getIdentifier("icon_$code", "drawable", context.packageName)
    if (id == 0) id = res.getIdentifier("icon_${code.take(2)}d", "drawable", context.packageName)
    if (id == 0) id = android.R.drawable.ic_menu_help
    Image(painterResource(id), contentDescription = code, modifier = Modifier.size(size))
}

@Composable
fun ChartScreen(city: String, modifier: Modifier) {
    var forecast by remember {mutableStateOf<List<ForecastItem>>(emptyList())}
    var error by remember {mutableStateOf<String?>(null)}
    var loading by remember {mutableStateOf(true)}

    LaunchedEffect(city) {
        try {
            forecast = withContext(Dispatchers.IO) {parseForecast(download("forecast", city))}
        } catch (e: CancellationException) {
            throw e
        } catch (e : Exception) {
            error = errorText(e)
        } finally {
            loading = false
        }
    }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Graf teplot: $city", fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text("Předpověď na 5 dní po 3 hodinách", style = MaterialTheme.typography.bodySmall)

        if(loading) CircularProgressIndicator()
        error?.let {
            Text(it, color = MaterialTheme.colorScheme.error)
        }

        if (forecast.isNotEmpty()) {
            TemperatureChart(forecast, Modifier.fillMaxSize().height(320.dp))
        }
    }
}

@Composable
fun TemperatureChart(items: List<ForecastItem>, modifier: Modifier) {
    val textColor = MaterialTheme.colorScheme.onSurface.toArgb()
    val lineColor = MaterialTheme.colorScheme.primary.toArgb()
    val fmt = remember {SimpleDateFormat("EEE HH:mm", Locale.forLanguageTag("cs"))}

    AndroidView(
        modifier = modifier,
        factory = { context ->
            LineChart(context).apply {
                description.isEnabled = false
                legend.isEnabled = false
                axisRight.isEnabled = false
                setTouchEnabled(true)
                isDragEnabled = true
                setScaleEnabled(true)
                setPinchZoom(true)

                xAxis.position = XAxis.XAxisPosition.BOTTOM
                xAxis.granularity = 1f
                xAxis.setDrawGridLines(false)
                xAxis.labelRotationAngle = -45f
            }
        },

        update = { chart ->
            val entries = items.mapIndexed { i, f -> Entry(i.toFloat(), f.temp.toFloat()) }

            val dataSet = LineDataSet(entries, "Teplota").apply {
                color = lineColor
                setCircleColor(lineColor)
                lineWidth = 2f
                circleRadius = 3f
                setDrawValues(false)
                mode = LineDataSet.Mode.CUBIC_BEZIER
                setDrawFilled(true)
                fillColor = lineColor
                fillAlpha = 50
            }

            chart.xAxis.valueFormatter = object : ValueFormatter() {
                override fun getFormattedValue(value: Float): String {
                    val i = value.toInt()
                    return if (i in items.indices) fmt.format(Date(items[i].time * 1000)) else ""
                }
            }

            chart.axisLeft.valueFormatter = object  : ValueFormatter() {
                override fun getFormattedValue(value: Float) = "%.0f °C".format(value)
            }

            chart.xAxis.textColor = textColor
            chart.axisLeft.textColor = textColor

            chart.data = LineData(dataSet)
            chart.setVisibleXRangeMaximum(12f)
            chart.moveViewToX(0f)
            chart.animateX(800)
        }
    )
}

@Composable
fun FavoritesScreen(
    favorites: List<String>,
    onAdd: (String) -> Unit,
    onRemove: (String) -> Unit,
    onSelect: (String) -> Unit,
    modifier: Modifier
) {
    var input by rememberSaveable { mutableStateOf("") }

    Column(
        modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("Oblíbená města", fontSize = 24.sp, fontWeight = FontWeight.Bold)

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = input,
                onValueChange = {input = it},
                label = {Text("Přidat město")},
                singleLine = true,
                modifier = Modifier.weight(1f)
            )

            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    onAdd(input.trim())
                    input = ""
                },
                enabled = input.isNotBlank()
            ) {
                Text("Přidat")
            }
        }

        if (favorites.isEmpty()) {
            Text("Žádná oblíbená místa", style = MaterialTheme.typography.bodyMedium)
        }

        LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(favorites, key = {it}) { c ->
                Card(onClick = { onSelect(c) }, modifier = Modifier.fillMaxWidth()) {
                    Row(
                        Modifier.padding(start = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.LocationOn, contentDescription = null)
                        Spacer(Modifier.width(12.dp))
                        Text(c, fontSize = 18.sp, modifier = Modifier.weight(1f))
                        IconButton(onClick = {onRemove(c)}) {
                            Icon(Icons.Default.Delete, contentDescription = "Smat z oblíbených")
                        }
                    }
                }
            }
        }
    }
}