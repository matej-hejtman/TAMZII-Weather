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
const val API_KEY = "8995127544e8f1ab81935b394ced520d"
data class Weather(val temp: Double, val description: String, val icon: String)
data class ForecastItem(val time: Long, val temp: Double, val description: String, val icon: String)
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
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    WeatherApp(Modifier.padding(innerPadding))
                }
            }
        }
    }
}
@Composable
fun WeatherApp(modifier: Modifier) {
    val prefs = LocalContext.current.getSharedPreferences("weather", Context.MODE_PRIVATE)
    var forecastCity by rememberSaveable { mutableStateOf<String?>(null) }

    val city = forecastCity
    if (city != null) {
        BackHandler { forecastCity = null }
        ForecastScreen(city, onBack = { forecastCity = null }, modifier)
    } else {
        CurrentScreen(prefs, onForecast = { forecastCity = it }, modifier)
    }
}
@Composable
fun CurrentScreen(prefs: SharedPreferences, onForecast: (String) -> Unit, modifier: Modifier) {
    var input by rememberSaveable { mutableStateOf(prefs.getString("city", "Ostrava") ?: "Ostrava") }
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
        val city = input.trim()
        scope.launch {
            loading = true
            error = null
            try {
                weather = withContext(Dispatchers.IO) { parseWeather(download("weather", city)) }
                shownCity = city
                prefs.edit().putString("city", city).apply()
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

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { refresh() }, enabled = !loading) { Text("Aktualizovat") }
            Button(
                onClick = { onForecast(shownCity) },
                enabled = weather != null && !loading
            ) { Text("Předpověď") }
        }

        if (loading) CircularProgressIndicator()
        error?.let { Text(it, color = MaterialTheme.colorScheme.error) }

        weather?.let { w ->
            Text(shownCity, fontSize = 20.sp)
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
fun ForecastScreen(city: String, onBack: () -> Unit, modifier: Modifier) {
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
            Button(onClick = onBack) { Text("Zpět") }
            Spacer(Modifier.width(12.dp))
            Text("Předpověď: $city", fontSize = 20.sp, fontWeight = FontWeight.Bold)
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