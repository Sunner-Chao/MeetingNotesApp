package com.oa.automation.ui.screen.recording

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.oa.automation.data.local.ConfigDataStore
import com.oa.automation.infrastructure.account.AccountApiService
import com.oa.automation.infrastructure.service.RoomWorkspaceController

class RoomWorkspaceViewModel(config: ConfigDataStore, api: AccountApiService) : ViewModel() {
    val controller = RoomWorkspaceController(config.authSessionFlow, config.accountEndpointFlow, api, viewModelScope)
}
