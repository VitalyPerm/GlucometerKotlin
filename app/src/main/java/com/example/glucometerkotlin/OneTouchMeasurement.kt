package com.example.glucometerkotlin

import java.util.Date

data class OneTouchMeasurement(
    val mGlucose: Float,
    val mDate: Date,
    val mId: String,
    val mErrorId: String
)