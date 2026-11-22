package com.fauxx.data.model

/**
 * Which synthetic pages Fauxx draws as if they were on screen (#322 item 11).
 *
 * The pool's WebViews are never attached to a window, so a page gets about 17 animation frames a
 * second instead of about 60 and its CSS animations never advance: a script that times frames or
 * watches an animation can tell nobody is looking. Putting the WebView on a private virtual
 * display the app owns makes it render like a page on screen. That costs battery, because the
 * page is really drawn, so it is a setting.
 *
 * - [ALL_PAGES]: every synthetic page is drawn. The default.
 * - [SEARCH_ONLY]: only search results pages are drawn; a result the search goes on to open is
 *   not, nor are the other modules' pages.
 * - [OFF]: nothing is drawn, the behavior before this setting.
 */
enum class PageRendering { ALL_PAGES, SEARCH_ONLY, OFF }
