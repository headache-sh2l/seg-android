package com.example.segandroid

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.example.segandroid.segmenter.BenchmarkScreen
import com.example.segandroid.segmenter.HistoryScreen
import com.example.segandroid.segmenter.SegmenterTestScreen
import com.example.segandroid.ui.theme.SegAndroidTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SegAndroidTheme {
                var selectedTab by remember { mutableStateOf(0) }
                val tabs = listOf("Segment", "Benchmark", "History")

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Column(modifier = Modifier.padding(innerPadding)) {
                        TabRow(selectedTabIndex = selectedTab) {
                            tabs.forEachIndexed { index, title ->
                                Tab(
                                    selected = selectedTab == index,
                                    onClick = { selectedTab = index },
                                    text = { Text(title) }
                                )
                            }
                        }
                        when (selectedTab) {
                            0 -> SegmenterTestScreen(modifier = Modifier.fillMaxSize())
                            1 -> BenchmarkScreen(modifier = Modifier.fillMaxSize())
                            else -> HistoryScreen(modifier = Modifier.fillMaxSize())
                        }
                    }
                }
            }
        }
    }
}
