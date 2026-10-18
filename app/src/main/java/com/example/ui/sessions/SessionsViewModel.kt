package com.example.ui.sessions

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.usage.DetailedSession
import com.example.usage.SessionsDashboardData
import com.example.usage.SessionsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

class SessionsViewModel : ViewModel() {

    private val _dashboardData = MutableStateFlow<SessionsDashboardData?>(null)
    val dashboardData: StateFlow<SessionsDashboardData?> = _dashboardData.asStateFlow()

    private val _selectedCalendar = MutableStateFlow(Calendar.getInstance())
    val selectedCalendar: StateFlow<Calendar> = _selectedCalendar.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0: App Statistics, 1: Session Timeline
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _selectedPeriod = MutableStateFlow("Daily") // "Daily", "Weekly"
    val selectedPeriod: StateFlow<String> = _selectedPeriod.asStateFlow()

    private val _selectedSessionForDetail = MutableStateFlow<DetailedSession?>(null)
    val selectedSessionForDetail: StateFlow<DetailedSession?> = _selectedSessionForDetail.asStateFlow()

    private var liveTickerJob: Job? = null
    private var lastRecordedDateString: String = ""

    fun loadData(context: Context) {
        val today = Calendar.getInstance()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        val todayString = dateFormat.format(today.time)

        // Check if viewing "today" and if a midnight rollover occurred
        val currentSelected = _selectedCalendar.value
        val isViewingToday = isSameDay(currentSelected, today) || (lastRecordedDateString.isNotBlank() && lastRecordedDateString != todayString && isSameDay(currentSelected, parseCalendar(lastRecordedDateString)))

        if (isViewingToday) {
            _selectedCalendar.value = Calendar.getInstance()
        }
        lastRecordedDateString = todayString

        viewModelScope.launch(Dispatchers.IO) {
            val data = SessionsRepository.getSessionsDashboardData(context, _selectedCalendar.value)
            _dashboardData.value = data

            startLiveTicker(context)
        }
    }

    private fun startLiveTicker(context: Context) {
        liveTickerJob?.cancel()
        liveTickerJob = viewModelScope.launch {
            val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            while (true) {
                delay(1000L)
                val now = Calendar.getInstance()
                val nowDateString = dateFormat.format(now.time)

                // Detect midnight rollover
                if (lastRecordedDateString.isNotBlank() && nowDateString != lastRecordedDateString) {
                    lastRecordedDateString = nowDateString
                    val isViewingToday = isSameDay(_selectedCalendar.value, now)
                    if (isViewingToday) {
                        _selectedCalendar.value = Calendar.getInstance()
                    }
                    loadData(context)
                    break
                }

                val current = _dashboardData.value
                if (current != null && current.isScreenActive) {
                    _dashboardData.value = current.copy(
                        currentSessionDurationMs = current.currentSessionDurationMs + 1000L,
                        screenOnMs = current.screenOnMs + 1000L
                    )
                }
            }
        }
    }

    fun setSelectedTab(tab: Int) {
        _selectedTab.value = tab
    }

    fun setSelectedPeriod(period: String) {
        _selectedPeriod.value = period
    }

    fun selectSessionForDetail(session: DetailedSession?) {
        _selectedSessionForDetail.value = session
    }

    fun navigateDate(context: Context, daysOffset: Int) {
        val newCal = _selectedCalendar.value.clone() as Calendar
        newCal.add(Calendar.DAY_OF_YEAR, daysOffset)

        val today = Calendar.getInstance()
        // Block future date navigation
        if (newCal.get(Calendar.YEAR) > today.get(Calendar.YEAR) ||
            (newCal.get(Calendar.YEAR) == today.get(Calendar.YEAR) && newCal.get(Calendar.DAY_OF_YEAR) > today.get(Calendar.DAY_OF_YEAR))) {
            return
        }

        _selectedCalendar.value = newCal
        loadData(context)
    }

    fun goToToday(context: Context) {
        _selectedCalendar.value = Calendar.getInstance()
        loadData(context)
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }

    private fun parseCalendar(dateStr: String): Calendar {
        val cal = Calendar.getInstance()
        try {
            val d = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).parse(dateStr)
            if (d != null) cal.time = d
        } catch (_: Exception) {}
        return cal
    }

    override fun onCleared() {
        super.onCleared()
        liveTickerJob?.cancel()
    }
}
