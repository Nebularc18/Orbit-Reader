package app.bookorbit.community.data

import android.content.Context
import android.net.ConnectivityManager

// A LAN connection can reach a self-hosted server without validated internet access.
fun networkAvailable(context: Context): Boolean =
    context.getSystemService(ConnectivityManager::class.java)?.activeNetwork != null
