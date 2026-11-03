package com.example.fatfish

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel

/** 对话编排状态机：鱼的动画、录音、播放都跟着这个状态走 */
class FishViewModel : ViewModel() {

    private val _state = MutableLiveData(FishState.IDLE)
    val state: LiveData<FishState> get() = _state

    fun startListening() { _state.value = FishState.LISTENING }
    fun startThinking() { _state.value = FishState.THINKING }
    fun startSpeaking() { _state.value = FishState.SPEAKING }
    fun setIdle() { _state.value = FishState.IDLE }
    fun setHappy() { _state.value = FishState.HAPPY }
    fun setSleepy() { _state.value = FishState.SLEEPY }
}
