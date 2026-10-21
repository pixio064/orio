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
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Calendar

data class DateItem(
    val dayOfWeek: String,
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
    val canNavigateNextWeek: Boolean = false,
    val errorMessage: String? = null
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

    private var loadJob: Job? = null

    init {
        updateWeekDates(_uiState.value.selectedCalendar)
    }

    fun loadData(context: Context) {
        val currentCal = _uiState.value.selectedCalendar
        loadJob?.cancel()
        loadJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.update { it.copy(isLoading = true, errorMessage = null) }

                val hourlyData = UsageStatsHelper.queryAppHourlyUsage(
                    context = context,
                    packageName = packageName,
                    targetCalendar = currentCal
                )

                val app = context.applicationContext as? FocusTrackApp
                val limit = app?.database?.appLimitDao()?.getLimitByPackage(packageName)

                _uiState.update {
                    it.copy(
                        appName = hourlyData.appName,
                        hourlyData = hourlyData,
                        appLimit = limit,
                        isLoading = false
                    )
                }
            } catch (e: Throwable) {
                _uiState.update { it.copy(isLoading = false, errorMessage = e.message) }
            }
        }
    }

    fun selectDate(context: Context, calendar: Calendar) {
        val today = Calendar.getInstance()
        if (calendar.after(today) && !isSameDay(calendar, today)) {
            return
        }

        _uiState.update {
            it.copy(
                selectedCalendar = calendar,
                selectedHourForScreenTime = null,
                selectedHourForNotification = null
            )
        }
        updateWeekDates(calendar)
        loadData(context)
    }

    fun navigateWeek(context: Context, offsetWeeks: Int) {
        val newCal = _uiState.value.selectedCalendar.clone() as Calendar
        newCal.add(Calendar.WEEK_OF_YEAR, offsetWeeks)

        val today = Calendar.getInstance()
        if (offsetWeeks > 0) {
            if (newCal.after(today)) {
                newCal.time = today.time
            }
        }

        selectDate(context, newCal)
    }

    fun toggleWeeklyView(context: Context) {
        _uiState.update { it.copy(isWeeklyView = !it.isWeeklyView) }
    }

    fun selectHourScreenTime(hour: Int?) {
        _uiState.update { it.copy(selectedHourForScreenTime = hour) }
    }

    fun selectHourNotification(hour: Int?) {
        _uiState.update { it.copy(selectedHourForNotification = hour) }
    }

    fun showLimitDialog(show: Boolean) {
        _uiState.update { it.copy(showLimitDialog = show) }
    }

    fun saveLimit(
        context: Context,
        limitMinutes: Int,
        warningPercent: Int,
        notifyOnWarning: Boolean,
        notifyOnLimit: Boolean
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as? FocusTrackApp ?: return@launch
            val entity = AppLimitEntity(
                packageName = packageName,
                appName = _uiState.value.appName.ifBlank { packageName },
                limitMinutes = limitMinutes,
                warningThresholdPercent = warningPercent,
                isEnabled = true,
                notifyOnLimit = notifyOnLimit,
                notifyOnWarning = notifyOnWarning
            )
            app.database.appLimitDao().insertOrUpdate(entity)
            _uiState.update { it.copy(appLimit = entity, showLimitDialog = false) }
        }
    }

    fun removeLimit(context: Context) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = context.applicationContext as? FocusTrackApp ?: return@launch
            app.database.appLimitDao().deleteByPackage(packageName)
            _uiState.update { it.copy(appLimit = null, showLimitDialog = false) }
        }
    }

    private fun updateWeekDates(selectedCal: Calendar) {
        val weekList = mutableListOf<DateItem>()
        val cal = selectedCal.clone() as Calendar

        cal.firstDayOfWeek = Calendar.MONDAY
        cal.set(Calendar.DAY_OF_WEEK, Calendar.MONDAY)

        val today = Calendar.getInstance()
        val dayNames = listOf("M", "T", "W", "T", "F", "S", "S")

        for (i in 0..6) {
            val dayCal = cal.clone() as Calendar
            val isSelected = isSameDay(dayCal, selectedCal)
            val isToday = isSameDay(dayCal, today)
            val isFuture = dayCal.after(today) && !isToday

            weekList.add(
                DateItem(
                    dayOfWeek = dayNames[i],
                    dayOfMonth = dayCal.get(Calendar.DAY_OF_MONTH),
                    calendar = dayCal,
                    isSelected = isSelected,
                    isToday = isToday,
                    isFuture = isFuture
                )
            )
            cal.add(Calendar.DAY_OF_MONTH, 1)
        }

        val weekEnd = cal.clone() as Calendar
        val canNext = !weekEnd.after(today)

        _uiState.update {
            it.copy(
                weekDates = weekList,
                canNavigateNextWeek = canNext
            )
        }
    }

    private fun isSameDay(c1: Calendar, c2: Calendar): Boolean {
        return c1.get(Calendar.YEAR) == c2.get(Calendar.YEAR) &&
                c1.get(Calendar.DAY_OF_YEAR) == c2.get(Calendar.DAY_OF_YEAR)
    }

    class Factory(private val packageName: String) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            return AppUsageDetailViewModel(packageName) as T
        }
    }
}

typealias AppUsageDetailViewModelFactory = AppUsageDetailViewModel.Factory
