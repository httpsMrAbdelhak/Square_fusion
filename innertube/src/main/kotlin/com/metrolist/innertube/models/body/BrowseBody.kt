package com.metrolist.innertube.models.body

import com.metrolist.innertube.models.Context
import com.metrolist.innertube.models.Continuation
import kotlinx.serialization.Serializable

@Serializable
data class BrowseBody(
    val context: Context,
    val browseId: String?,
    val params: String?,
    val continuation: String?,
    /** Square Fusion: the chosen filters of a page that has them, such as the charts' country. */
    val formData: FormData? = null,
)

@Serializable
data class FormData(val selectedValues: List<String>)
