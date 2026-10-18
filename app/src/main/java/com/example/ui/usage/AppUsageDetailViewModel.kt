package com.example.ui.usage

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.FocusTrackApp
import com.example.data.local.entity.AppLimitEntity
import com.example.usage.AppHourlyUsageData
import com.example.usage.UsageStatsHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.Calendar

data class DateItem(
    val dayOfWeek: String, // "M", "T", "W", "T", "F", "S", "S"
    val dayOfMonth: Int,
    val calendar: Calendar,
    val isSelected: Boolean,
    val isToday: Boolean,
    val isFuture: Boolean = false
)

data class AppDetailUiState(
    val packageName: String = "",
    val appName: String = "",
    val selectedCalendar: Calendar = Calendar.getInstance(),
    val weekDates: List<DateItem> = emptyList(),
    val isWeeklyView: Boolean = false,
    val hourlyData: AppHourlyUsageData? = null,
    val appLimit: AppLimitEntity? = null,
    val selectedHourForScreenTime: Int? = null,
    val selectedHourForNotification: Int? = null,
    val showLimitDialog: Boolean = false,
    val isLoading: Boolean = true,
    val canNavigateNextWeek: Boolean = false
)

class AppUsageDetailViewModel(
    private val packageName: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(
        AppDetailUiState(
            packageName = packageName,
            selectedCalendar = Calendar.getInstance()
        )
    )
    val uiState: StateFlow<AppDetailUiState> = _uiState.asStateFlow()

    init {
        updateWeekDates(_uiState.value.selectedCalendar)
    }

    fun loadData(context: Context) {
        val currentCal = _uiState.value.selectedCalendar
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isLoading = true)

            val hourlyData = UsageStatsHelper.queryAppHourlyUsage(
                context = context,
                packageName = packageName,
                targetCalendar = currentCal
            )

            val app = context.applicationContext as? FocusTrackApp
            val limit = app?.database?.appLimitDao()?.getLimitByPackage(packageName)

            _uiState.value = _uiState.value.copy(
                appName = hourlyData.appName,
                hourlyData = hourlyData,
                appLimit = limit,
                isLoading = false
            )
        }
    }

    fun selectDate(context: Context, calendar: Calendar) {
        val today = Calendar.getInstance()
        // Prevent selecting future dates
        if (calendar.after(today) && !isSameDay(calendar, today)) {
            return
        }

        _uiState.value = _uiState.value.copy(
            selectedCalendar = calendar,
            selectedHourForScreenTime = null,
            selectedHourForNotification = null
        )
        updateWeekDates(calendar)
        loadData(context)
    }

    fun navigateWeek(context: Context, offsetWeeks: Int) {
        val newCal = _uiState.value.selectedCalendar.clone() as Calendar
        newCal.add(Calendar.WEEK_OF_YEAR, offsetWeeks)

        val today = Calendar.getInstance()
        // If navigating forward into the future, clamp to today
        if (offsetWeeks > 0) {
            if (newCal.after(today)) {
                newCal.time = today.time
            }
        }

        selectDate(context, newCal)
    }

    fun toggleWeeklyView(context: Context) {
        val newMode = !_uiState.value.isWeeklyView
        _uiState.value = _uiState.value.copy(isWeeklyView = newMode)
    }

    fun selectHourScreenTime(hour: Int?) {
        _uiState.value = _uiState.value.copy(selectedHourForScreenTime = hour)
    }

    fun selectHourNotification(hour: Int?) {
        _uiState.value = _uiState.value.copy(selectedHourForNotification = hour)
    }

    fun showLimitDialog(show: Boolean) {
        _uiState.value = _uiState.value.copy(showLimitDialog = show)
    }

    fun saveLimit(context: Context, limitMinutes: Int, warningPercent: Int, notify: Boolean) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as? FocusTrackApp ?: return@launch
            val entity = AppLimitEntity(
                packageName = packageName,
                appName = _uiState.value.appName.ifBlank { packageName },
                limitMinutes = limitMinutes,
                warningThresholdPercent = warningPercent,
                isEnabled = true,
                notifyOnLimit = notify,
                notifyOnWarning = notify
            )
            app.database.appLimitDao().insertOrUpdate(entity)
            _uiState.value = _uiState.value.copy(appLimit = entity, showLimitDialog = false)
        }
    }

    fun removeLimit(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as? FocusTrackApp ?: return@launch
            app.database.appLimitDao().deleteByPackage(packageName)
            _uiState.value = _uiState.value.copy(appLimit = null, showLimitDialog = false)
        }
    }

    private fun updateWeekDates(selectedCal: Calendar) {
        val weekList = mutableListOf<DateItem>()
        val startOfWeek = selectedCal.clone() as Calendar
        startOfWeek.firstDayOfWeek = Calendar.MONDAY
        val currentDayOfWeek = startOfWeek.get(Calendar.DAY_OF_WEEK)
        val daysFromMonday = (currentDayOfWeek - Calendar.MONDAY + 7) % 7
        startOfWeek.add(Calendar.DAY_OF_MONTH, -daysFromMonday)

        val todayCal = Calendar.getInstance()
        val dayLetters = listOf("M", "T", "W", "T", "F", "S", "S")

        var anyDayInWeekBeforeToday = false

        for (i in 0..6) {
            val dayCal = startOfWeek.clone() as Calendar
            dayCal.add(Calendar.DAY_OF_MONTH, i)

            val isSelected = isSameDay(dayCal, selectedCal)
            val isToday = isSameDay(dayCal, todayCal)
            val isFuture = dayCal.get(Calendar.YEAR) > todayCal.get(Calendar.YEAR) ||
                    (dayCal.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) && dayCal.get(Calendar.DAY_OF_YEAR) > todayCal.get(Calendar.DAY_OF_YEAR))

            if (!isFuture) {
                anyDayInWeekBeforeToday = true
            }

            weekList.add(
                DateItem(
                    dayOfWeek = dayLetters[i],
                    dayOfMonth = dayCal.get(Calendar.DAY_OF_MONTH),
                    calendar = dayCal,
                    isSelected = isSelected,
                    isToday = isToday,
                    isFuture = isFuture
                )
            )
        }

        // Can navigate next week only if start of next week is <= today
        val nextWeekStart = startOfWeek.clone() as Calendar
        nextWeekStart.add(Calendar.DAY_OF_MONTH, 7)
        val canNext = nextWeekStart.get(Calendar.YEAR) < todayCal.get(Calendar.YEAR) ||
                (nextWeekStart.get(Calendar.YEAR) == todayCal.get(Calendar.YEAR) && nextWeekStart.get(Calendar.DAY_OF_YEAR) <= todayCal.get(Calendar.DAY_OF_YEAR))

        _uiState.value = _uiState.value.copy(
            weekDates = weekList,
            canNavigateNextWeek = canNext
        )
    }

    private fun isSameDay(cal1: Calendar, cal2: Calendar): Boolean {
        return cal1.get(Calendar.YEAR) == cal2.get(Calendar.YEAR) &&
                cal1.get(Calendar.DAY_OF_YEAR) == cal2.get(Calendar.DAY_OF_YEAR)
    }
}

class AppUsageDetailViewModelFactory(
    private val packageName: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return AppUsageDetailViewModel(packageName) as T
    }
}
