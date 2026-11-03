package com.example.fatfish

/** 大肥鱼的对话/情绪状态，驱动动画动作 */
enum class FishState {
    IDLE,       // 待机：游动 + 呼吸
    LISTENING,  // 倾听：凑近 + 睁大眼睛
    THINKING,   // 思考：冒泡泡
    SPEAKING,   // 说话：嘴巴张合
    HAPPY,      // 开心：蹦跳 + 拍尾
    SLEEPY      // 困了：闭眼 + 下沉
}
