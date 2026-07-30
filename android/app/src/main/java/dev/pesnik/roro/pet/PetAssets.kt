package dev.pesnik.roro.pet

import androidx.annotation.DrawableRes
import dev.pesnik.roro.R

object PetAssets {
    @DrawableRes
    fun forState(state: PetState): Int = when (state) {
        PetState.Idle -> R.drawable.calico_idle
        PetState.Thinking -> R.drawable.calico_thinking
        PetState.Working -> R.drawable.calico_working_typing
        PetState.Attention -> R.drawable.calico_happy
        PetState.Error -> R.drawable.calico_error
    }
}
