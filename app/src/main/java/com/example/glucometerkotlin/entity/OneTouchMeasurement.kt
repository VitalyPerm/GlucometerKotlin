package com.example.glucometerkotlin.entity

import java.util.Date

data class OneTouchMeasurement(
    val glucose: Float,
    val date: Date,
    val id: String,
    val errorId: Int
)