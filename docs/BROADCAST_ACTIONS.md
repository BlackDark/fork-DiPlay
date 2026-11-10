# Broadcast actions

The experimental Advanced setting listens while DiPlay's process is running. Other apps may spoof exported broadcasts, so configure only actions appropriate for the head unit. Android may also restrict opening an activity from the background.

Each action always matches broadcasts with no data or MIME type. Optionally specify one lowercase URI scheme and/or one lowercase MIME type to add a data-bearing match for that action. When both are specified, both must match. MIME-only matching follows Android's content/file URI rules. Intent categories are not supported. Remove and re-add an action to change its data filter.

Custom broadcast actions, data filters, and their enabled state are not included in DiPlay's settings backup. Configure them again after restoring a backup.
