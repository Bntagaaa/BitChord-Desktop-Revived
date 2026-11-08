package com.music.bitchord.ui.player

/** Desktop keeps a local liked library; mobile requires an account or a local track. */
internal fun landscapeLikeVisible(signedIn: Boolean, localSong: Boolean, desktop: Boolean): Boolean =
    signedIn || localSong || desktop
