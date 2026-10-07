# Material Components 1.1.0 local patch

`ui/components/drop_down_menu.slint`: initialize the displayed selection when
creating a dropdown, refresh it when its items model changes, and bounds-check
the requested index rather than the previous selection. Without this patch,
static initial selections are blank, and refreshing an output list can leave
an outdated device name on screen. All other upstream files are unchanged.
