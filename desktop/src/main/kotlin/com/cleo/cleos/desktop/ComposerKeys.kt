package com.cleo.cleos.desktop

internal enum class EnterAction { Pass, Consume, Send }
/** Hold the same Enter through IME confirmation and key-repeat; a new press starts at key-up. */
internal class ComposerEnterGate {
    private var held=false
    fun release(){held=false}
    fun press(composing:Boolean,shift:Boolean,alt:Boolean,meta:Boolean,blocked:Boolean):EnterAction {
        if(shift || alt || meta)return EnterAction.Pass
        val repeated=held;held=true
        if(composing)return EnterAction.Pass
        return if(!repeated && !blocked)EnterAction.Send else EnterAction.Consume
    }
}
