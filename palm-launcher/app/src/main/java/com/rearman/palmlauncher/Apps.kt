package com.rearman.palmlauncher

import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Drawable
import android.os.Build

class AppEntry(val component: String, val packageName: String, val label: String, val icon: Drawable)

object Apps {
	// Lists every launcher activity except our own. Unsorted; the caller sorts through LauncherState.filter.
	fun load(ctx: Context): List<AppEntry> {
		val pm = ctx.packageManager
		val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
		return pm.queryIntentActivities(intent, 0)
			.filter { it.activityInfo.packageName != ctx.packageName }
			.map { ri ->
				val pkg = ri.activityInfo.packageName
				AppEntry(
					component = ComponentName(pkg, ri.activityInfo.name).flattenToString(),
					packageName = pkg,
					label = ri.loadLabel(pm).toString(),
					icon = ri.loadIcon(pm),
				)
			}
	}

	fun launch(ctx: Context, component: String) {
		val cn = ComponentName.unflattenFromString(component) ?: return
		val intent = Intent(Intent.ACTION_MAIN)
			.addCategory(Intent.CATEGORY_LAUNCHER)
			.setComponent(cn)
			.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
		try {
			ctx.startActivity(intent)
		} catch (e: ActivityNotFoundException) {
			// The app was removed between load and launch. Do nothing.
		}
	}

	// The caller must unregister the returned receiver.
	fun watch(ctx: Context, onChange: () -> Unit): BroadcastReceiver {
		val r = object : BroadcastReceiver() {
			override fun onReceive(context: Context, intent: Intent) {
				onChange()
			}
		}
		val filter = IntentFilter().apply {
			addAction(Intent.ACTION_PACKAGE_ADDED)
			addAction(Intent.ACTION_PACKAGE_REMOVED)
			addAction(Intent.ACTION_PACKAGE_CHANGED)
			addAction(Intent.ACTION_PACKAGE_REPLACED)
			addDataScheme("package")
		}
		if (Build.VERSION.SDK_INT >= 33) {
			ctx.registerReceiver(r, filter, Context.RECEIVER_EXPORTED)
		} else {
			ctx.registerReceiver(r, filter)
		}
		return r
	}
}
