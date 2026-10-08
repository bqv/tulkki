package uk.xa0.tulkki.data

import android.content.Context
import java.io.File

/**
 * The smallest `Context` that lets Room's *driver* path build a database on the host.
 *
 * Stub for stub from `android.content.Context` in the SDK's `android.jar`, whose abstract
 * methods throw "not mocked" if they are ever called. They are not: with `setDriver` the
 * open-helper path - and with it the database path, the file system and the package manager - is
 * replaced. This class exists to satisfy Kotlin's non-null parameter check on
 * `Room.databaseBuilder(context, ...)`, and `getDatabasePath` answers a real path anyway so a
 * future Room that consults it is not handed a null. Parameter names are `p0`, `p1`, ... because
 * `javap` prints types only; a stub needs a name for each.
 */
class HostContext : Context() {

    override fun getApplicationContext(): Context {
        return this
    }

    override fun getDatabasePath(name: String): File {
        return File(System.getProperty("java.io.tmpdir", "."), name)
    }

    override fun bindService(service: android.content.Intent, conn: android.content.ServiceConnection, flags: Int): Boolean {
        return false
    }

    override fun checkCallingOrSelfPermission(permission: String): Int {
        return 0
    }

    override fun checkCallingOrSelfUriPermission(uri: android.net.Uri, modeFlags: Int): Int {
        return 0
    }

    override fun checkCallingPermission(permission: String): Int {
        return 0
    }

    override fun checkCallingUriPermission(uri: android.net.Uri, modeFlags: Int): Int {
        return 0
    }

    override fun checkPermission(permission: String, pid: Int, uid: Int): Int {
        return 0
    }

    override fun checkSelfPermission(permission: String): Int {
        return 0
    }

    override fun checkUriPermission(uri: android.net.Uri, pid: Int, uid: Int, modeFlags: Int): Int {
        return 0
    }

    override fun checkUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int): Int {
        return 0
    }

    override fun clearWallpaper() {
    }

    override fun createConfigurationContext(overrideConfiguration: android.content.res.Configuration): android.content.Context? {
        return null
    }

    override fun createContextForSplit(splitName: String): android.content.Context? {
        return null
    }

    override fun createDeviceProtectedStorageContext(): android.content.Context? {
        return null
    }

    override fun createDisplayContext(display: android.view.Display): android.content.Context? {
        return null
    }

    override fun createPackageContext(packageName: String, flags: Int): android.content.Context? {
        return null
    }

    override fun databaseList(): Array<String>? {
        return null
    }

    override fun deleteDatabase(name: String): Boolean {
        return false
    }

    override fun deleteFile(name: String): Boolean {
        return false
    }

    override fun deleteSharedPreferences(name: String): Boolean {
        return false
    }

    override fun enforceCallingOrSelfPermission(permission: String, message: String?) {
    }

    override fun enforceCallingOrSelfUriPermission(uri: android.net.Uri, modeFlags: Int, message: String) {
    }

    override fun enforceCallingPermission(permission: String, message: String?) {
    }

    override fun enforceCallingUriPermission(uri: android.net.Uri, modeFlags: Int, message: String) {
    }

    override fun enforcePermission(permission: String, pid: Int, uid: Int, message: String?) {
    }

    override fun enforceUriPermission(uri: android.net.Uri, pid: Int, uid: Int, modeFlags: Int, message: String) {
    }

    override fun enforceUriPermission(uri: android.net.Uri?, readPermission: String?, writePermission: String?, pid: Int, uid: Int, modeFlags: Int, message: String?) {
    }

    override fun fileList(): Array<String>? {
        return null
    }

    override fun getApplicationInfo(): android.content.pm.ApplicationInfo? {
        return null
    }

    override fun getAssets(): android.content.res.AssetManager? {
        return null
    }

    override fun getCacheDir(): java.io.File? {
        return null
    }

    override fun getClassLoader(): java.lang.ClassLoader? {
        return null
    }

    override fun getCodeCacheDir(): java.io.File? {
        return null
    }

    override fun getContentResolver(): android.content.ContentResolver? {
        return null
    }

    override fun getDataDir(): java.io.File? {
        return null
    }

    override fun getDir(name: String, mode: Int): java.io.File? {
        return null
    }

    override fun getExternalCacheDir(): java.io.File? {
        return null
    }

    override fun getExternalCacheDirs(): Array<java.io.File>? {
        return null
    }

    override fun getExternalFilesDir(type: String?): java.io.File? {
        return null
    }

    override fun getExternalFilesDirs(type: String): Array<java.io.File>? {
        return null
    }

    override fun getExternalMediaDirs(): Array<java.io.File>? {
        return null
    }

    override fun getFileStreamPath(name: String): java.io.File? {
        return null
    }

    override fun getFilesDir(): java.io.File? {
        return null
    }

    override fun getMainLooper(): android.os.Looper? {
        return null
    }

    override fun getNoBackupFilesDir(): java.io.File? {
        return null
    }

    override fun getObbDir(): java.io.File? {
        return null
    }

    override fun getObbDirs(): Array<java.io.File>? {
        return null
    }

    override fun getPackageCodePath(): String? {
        return null
    }

    override fun getPackageManager(): android.content.pm.PackageManager? {
        return null
    }

    override fun getPackageName(): String? {
        return null
    }

    override fun getPackageResourcePath(): String? {
        return null
    }

    override fun getResources(): android.content.res.Resources? {
        return null
    }

    override fun getSharedPreferences(name: String, mode: Int): android.content.SharedPreferences? {
        return null
    }

    override fun getSystemService(name: String): Any? {
        return null
    }

    override fun getSystemServiceName(serviceClass: Class<*>): String? {
        return null
    }

    override fun getTheme(): android.content.res.Resources.Theme? {
        return null
    }

    override fun getWallpaper(): android.graphics.drawable.Drawable? {
        return null
    }

    override fun getWallpaperDesiredMinimumHeight(): Int {
        return 0
    }

    override fun getWallpaperDesiredMinimumWidth(): Int {
        return 0
    }

    override fun grantUriPermission(toPackage: String, uri: android.net.Uri, modeFlags: Int) {
    }

    override fun isDeviceProtectedStorage(): Boolean {
        return false
    }

    override fun moveDatabaseFrom(source: android.content.Context, name: String): Boolean {
        return false
    }

    override fun moveSharedPreferencesFrom(source: android.content.Context, name: String): Boolean {
        return false
    }

    override fun openFileInput(name: String): java.io.FileInputStream? {
        return null
    }

    override fun openFileOutput(name: String, mode: Int): java.io.FileOutputStream? {
        return null
    }

    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory): android.database.sqlite.SQLiteDatabase? {
        return null
    }

    override fun openOrCreateDatabase(name: String, mode: Int, factory: android.database.sqlite.SQLiteDatabase.CursorFactory, errorHandler: android.database.DatabaseErrorHandler?): android.database.sqlite.SQLiteDatabase? {
        return null
    }

    override fun peekWallpaper(): android.graphics.drawable.Drawable? {
        return null
    }

    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter): android.content.Intent? {
        return null
    }

    override fun registerReceiver(receiver: android.content.BroadcastReceiver?, filter: android.content.IntentFilter, flags: Int): android.content.Intent? {
        return null
    }

    override fun registerReceiver(receiver: android.content.BroadcastReceiver, filter: android.content.IntentFilter, broadcastPermission: String?, scheduler: android.os.Handler?): android.content.Intent? {
        return null
    }

    override fun registerReceiver(receiver: android.content.BroadcastReceiver, filter: android.content.IntentFilter, broadcastPermission: String?, scheduler: android.os.Handler?, flags: Int): android.content.Intent? {
        return null
    }

    override fun removeStickyBroadcast(intent: android.content.Intent) {
    }

    override fun removeStickyBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {
    }

    override fun revokeUriPermission(uri: android.net.Uri, modeFlags: Int) {
    }

    override fun revokeUriPermission(targetPackage: String, uri: android.net.Uri, modeFlags: Int) {
    }

    override fun sendBroadcast(intent: android.content.Intent) {
    }

    override fun sendBroadcast(intent: android.content.Intent, receiverPermission: String?) {
    }

    override fun sendBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {
    }

    override fun sendBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle, receiverPermission: String?) {
    }

    override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?) {
    }

    override fun sendOrderedBroadcast(intent: android.content.Intent, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver?, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {
    }

    override fun sendOrderedBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle, receiverPermission: String?, resultReceiver: android.content.BroadcastReceiver, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {
    }

    override fun sendStickyBroadcast(intent: android.content.Intent) {
    }

    override fun sendStickyBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle) {
    }

    override fun sendStickyOrderedBroadcast(intent: android.content.Intent, resultReceiver: android.content.BroadcastReceiver, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {
    }

    override fun sendStickyOrderedBroadcastAsUser(intent: android.content.Intent, user: android.os.UserHandle, resultReceiver: android.content.BroadcastReceiver, scheduler: android.os.Handler?, initialCode: Int, initialData: String?, initialExtras: android.os.Bundle?) {
    }

    override fun setTheme(resid: Int) {
    }

    override fun setWallpaper(bitmap: android.graphics.Bitmap) {
    }

    override fun setWallpaper(data: java.io.InputStream) {
    }

    override fun startActivities(intents: Array<android.content.Intent>) {
    }

    override fun startActivities(intents: Array<android.content.Intent>, options: android.os.Bundle) {
    }

    override fun startActivity(intent: android.content.Intent) {
    }

    override fun startActivity(intent: android.content.Intent, options: android.os.Bundle?) {
    }

    override fun startForegroundService(intent: android.content.Intent): android.content.ComponentName? {
        return null
    }

    override fun startInstrumentation(className: android.content.ComponentName, profileFile: String?, arguments: android.os.Bundle?): Boolean {
        return false
    }

    override fun startIntentSender(intentSender: android.content.IntentSender, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int) {
    }

    override fun startIntentSender(intentSender: android.content.IntentSender, fillInIntent: android.content.Intent?, flagsMask: Int, flagsValues: Int, extraFlags: Int, options: android.os.Bundle?) {
    }

    override fun startService(service: android.content.Intent): android.content.ComponentName? {
        return null
    }

    override fun stopService(service: android.content.Intent): Boolean {
        return false
    }

    override fun unbindService(conn: android.content.ServiceConnection) {
    }

    override fun unregisterReceiver(receiver: android.content.BroadcastReceiver) {
    }

}
