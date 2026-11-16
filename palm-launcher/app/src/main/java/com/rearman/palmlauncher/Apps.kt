package com.rearman.palmlauncher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager

// One launcher activity of one user profile (the phone's own user, a work profile, or the private space).
// `key` names it in LauncherState: see Apps.key.
class AppEntry(
	val component: ComponentName,
	val user: UserHandle,
	val key: String,
	val label: String,
	val icon: Drawable,
) {
	val packageName: String get() = component.packageName
}

object Apps {
	// The LauncherState key of an activity: ComponentName.flattenToString() for the launcher's own user, the
	// key of every launcher version before work profiles, so saved categories and dock slots still match;
	// for another profile that string, "#", and the profile's serial number, which stays the same across boots.
	fun key(ctx: Context, component: ComponentName, user: UserHandle): String =
		component.flattenToString() + profile(ctx, user)

	// The end of a key for `user`: "" for the launcher's own user, else "#" and the profile serial.
	fun profile(ctx: Context, user: UserHandle): String =
		if (user == Process.myUserHandle()) ""
		else "#" + ctx.getSystemService(UserManager::class.java).getSerialNumberForUser(user)

	// Lists every launcher activity of every profile except our own, or with `pkg` and `user` only those of that
	// package in that profile (after a package change, so the other icons stay loaded). A private space that is
	// locked shows no apps. Unsorted; the caller sorts through LauncherState.filter.
	fun load(ctx: Context, pkg: String? = null, user: UserHandle? = null): List<AppEntry> {
		val la = ctx.getSystemService(LauncherApps::class.java)
		val users = if (user != null) listOf(user) else la.profiles
		return users.filter { shows(ctx, it) }.flatMap { u ->
			la.getActivityList(pkg, u)
				.filter { !(it.componentName.packageName == ctx.packageName && u == Process.myUserHandle()) }
				.map { entry(ctx, it) }
		}
	}

	private fun entry(ctx: Context, info: LauncherActivityInfo): AppEntry {
		val pm = ctx.packageManager
		return AppEntry(
			component = info.componentName,
			user = info.user,
			key = key(ctx, info.componentName, info.user),
			label = info.label.toString(),
			// The system badge (the work briefcase) on the icon of an app in another profile; none on our own user.
			icon = pm.getUserBadgedIcon(info.getIcon(0), info.user),
		)
	}

	// False for a private space while it is locked (quiet mode): Android asks a launcher to hide its apps then.
	// A paused work profile still shows; a tap on one of its apps asks to turn work apps on.
	private fun shows(ctx: Context, user: UserHandle): Boolean {
		if (Build.VERSION.SDK_INT < 35 || user == Process.myUserHandle()) return true
		val la = ctx.getSystemService(LauncherApps::class.java)
		val isPrivate = try {
			la.getLauncherUserInfo(user)?.userType == UserManager.USER_TYPE_PROFILE_PRIVATE
		} catch (e: SecurityException) {
			// Not the default launcher: Android lists no private space to it, so this profile is not one.
			false
		}
		return !isPrivate || !ctx.getSystemService(UserManager::class.java).isQuietModeEnabled(user)
	}

	fun launch(ctx: Context, app: AppEntry) {
		try {
			ctx.getSystemService(LauncherApps::class.java).startMainActivity(app.component, app.user, null, null)
		} catch (e: ActivityNotFoundException) {
			// The app was removed between load and launch. Do nothing.
		} catch (e: SecurityException) {
			// The activity is no longer a launcher activity of that profile. Do nothing.
		}
	}

	// Opens the App info page of Settings for the app, in its own profile.
	fun showInfo(ctx: Context, app: AppEntry) {
		try {
			ctx.getSystemService(LauncherApps::class.java).startAppDetailsActivity(app.component, app.user, null, null)
		} catch (e: ActivityNotFoundException) {
		} catch (e: SecurityException) {
		}
	}

	// The uninstall request of the app, in its own profile.
	fun deleteIntent(app: AppEntry): Intent =
		Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null))
			.putExtra(Intent.EXTRA_USER, app.user)

	// True when `pkg` is gone from `user` with its data (the LauncherApps form of ACTION_PACKAGE_FULLY_REMOVED):
	// an app kept with its data, disabled, or on storage that is out stays installed.
	private fun fullyRemoved(ctx: Context, pkg: String, user: UserHandle): Boolean = try {
		ctx.getSystemService(LauncherApps::class.java).getApplicationInfo(pkg,
			PackageManager.MATCH_UNINSTALLED_PACKAGES or PackageManager.MATCH_DISABLED_COMPONENTS, user)
		false
	} catch (e: PackageManager.NameNotFoundException) {
		true
	}

	// Watches the apps of every profile. onChange(pkg, user) gets the package that was added, changed, or
	// removed, or null for both when many changed at once (storage, a profile locked or added). onRemoved(pkg,
	// user) gets a package removed with its data, whose categories and dock slots then go. The caller must pass
	// the returned Watch to stop.
	fun watch(
		ctx: Context,
		onChange: (pkg: String?, user: UserHandle?) -> Unit,
		onRemoved: (pkg: String, user: UserHandle) -> Unit,
	): Watch {
		val main = Handler(Looper.getMainLooper())
		val me = Process.myUserHandle()
		val callback = object : LauncherApps.Callback() {
			override fun onPackageAdded(packageName: String, user: UserHandle) = onChange(packageName, user)
			override fun onPackageChanged(packageName: String, user: UserHandle) = onChange(packageName, user)
			override fun onPackageRemoved(packageName: String, user: UserHandle) {
				onChange(packageName, user)
				// Our own user gets ACTION_PACKAGE_FULLY_REMOVED below; another profile has no such broadcast here.
				if (user != me && fullyRemoved(ctx, packageName, user)) onRemoved(packageName, user)
			}
			// Apps on adoptable or external storage in another profile. Our own user gets the broadcasts
			// ACTION_EXTERNAL_APPLICATIONS_AVAILABLE and UNAVAILABLE below, so it is not loaded twice.
			// Unavailable apps leave the list but keep their categories and dock slots.
			override fun onPackagesAvailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) {
				if (user != me) onChange(null, null)
			}
			override fun onPackagesUnavailable(packageNames: Array<String>, user: UserHandle, replacing: Boolean) {
				if (user != me) onChange(null, null)
			}
		}
		val la = ctx.getSystemService(LauncherApps::class.java)
		la.registerCallback(callback, main)
		val receiver = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				val pkg = intent.data?.schemeSpecificPart
				if (intent.action == Intent.ACTION_PACKAGE_FULLY_REMOVED) {
					if (pkg != null) onRemoved(pkg, me)
				} else {
					onChange(null, null)
				}
			}
		}
		val removed = IntentFilter(Intent.ACTION_PACKAGE_FULLY_REMOVED).apply { addDataScheme("package") }
		val profiles = IntentFilter().apply {
			addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_AVAILABLE)
			addAction(Intent.ACTION_EXTERNAL_APPLICATIONS_UNAVAILABLE)
			addAction(Intent.ACTION_MANAGED_PROFILE_ADDED)
			addAction(Intent.ACTION_MANAGED_PROFILE_REMOVED)
			addAction(Intent.ACTION_MANAGED_PROFILE_AVAILABLE)
			addAction(Intent.ACTION_MANAGED_PROFILE_UNAVAILABLE)
			// The private space (Android 15): locked and unlocked, added and removed.
			if (Build.VERSION.SDK_INT >= 35) {
				addAction(Intent.ACTION_PROFILE_AVAILABLE)
				addAction(Intent.ACTION_PROFILE_UNAVAILABLE)
				addAction(Intent.ACTION_PROFILE_ADDED)
				addAction(Intent.ACTION_PROFILE_REMOVED)
			}
		}
		for (f in listOf(removed, profiles)) {
			if (Build.VERSION.SDK_INT >= 33) {
				ctx.registerReceiver(receiver, f, Context.RECEIVER_EXPORTED)
			} else {
				ctx.registerReceiver(receiver, f)
			}
		}
		return Watch(la, callback, receiver)
	}

	class Watch(private val la: LauncherApps, private val callback: LauncherApps.Callback, private val receiver: BroadcastReceiver) {
		fun stop(ctx: Context) {
			la.unregisterCallback(callback)
			ctx.unregisterReceiver(receiver)
		}
	}
}
