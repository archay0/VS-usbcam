package com.arc.videoshuffle

import com.google.gson.annotations.SerializedName

data class TailscaleDeviceList(
    @SerializedName("devices") val devices: List<TailscaleDevice>
)

data class TailscaleDevice(
    @SerializedName("id") val id: String,
    @SerializedName("hostname") val hostname: String,
    @SerializedName("addresses") val addresses: List<String>,
    @SerializedName("os") val os: String,
    @SerializedName("user") val user: String,
    @SerializedName("connectedToControl") val connectedToControl: Boolean
)