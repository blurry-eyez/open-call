package xyz.s2105.opencall

import kotlinx.coroutines.flow.MutableStateFlow

object Store {
    /** Room code from a https://<your-server>/#code link, waiting to be put in the join form. */
    val roomLink = MutableStateFlow<String?>(null)
}
