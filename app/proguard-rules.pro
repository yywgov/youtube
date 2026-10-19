# JS bridge (window.YTL) must keep its method names
-keepclassmembers class com.google.android.youtube.pro.webview.WebAppInterface {
    @android.webkit.JavascriptInterface <methods>;
}
-keepattributes JavascriptInterface
# Custom WebView referenced from XML
-keep class com.google.android.youtube.pro.webview.YTProWebView { <init>(...); }
