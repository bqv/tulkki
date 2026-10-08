/*
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.xa0.tulkki.ui.utils

import android.content.Context
import android.content.res.loader.ResourcesLoader
import android.content.res.loader.ResourcesProvider
import android.os.ParcelFileDescriptor
import android.system.Os
import android.util.Log
import java.io.FileDescriptor
import java.io.FileOutputStream

/** This class creates a Resources Table at runtime and helps replace color Resources on the fly. */
object ColorResourcesLoaderCreator {

    private const val TAG = "ColorResourcesLoaderCreator"

    @JvmStatic
    fun create(context: Context, colorMapping: Map<Int, Int>): ResourcesLoader? {
        try {
            val contentBytes = ColorResourcesTableCreator.create(context, colorMapping)
            Log.i(TAG, "Table created, length: " + contentBytes.size)
            if (contentBytes.size == 0) {
                return null
            }
            var arscFile: FileDescriptor? = null
            try {
                arscFile = Os.memfd_create("temp.arsc", /* flags= */ 0)
                // Note: This must not be closed through the OutputStream.
                FileOutputStream(arscFile).use { pipeWriter ->
                    pipeWriter.write(contentBytes)

                    ParcelFileDescriptor.dup(arscFile).use { pfd ->
                        val colorsLoader = ResourcesLoader()
                        colorsLoader.addProvider(
                            ResourcesProvider.loadFromTable(pfd, /* assetsProvider= */ null)
                        )
                        return colorsLoader
                    }
                }
            } finally {
                if (arscFile != null) {
                    Os.close(arscFile)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create the ColorResourcesTableCreator.", e)
        }
        return null
    }
}
