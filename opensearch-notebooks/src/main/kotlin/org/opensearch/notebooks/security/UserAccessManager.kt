/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 *
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

/*
 * Copyright 2020 Amazon.com, Inc. or its affiliates. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License").
 * You may not use this file except in compliance with the License.
 * A copy of the License is located at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * or in the "license" file accompanying this file. This file is distributed
 * on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either
 * express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 */

package org.opensearch.notebooks.security

import org.opensearch.commons.authuser.User
import org.opensearch.notebooks.settings.PluginSettings
import org.opensearch.notebooks.settings.PluginSettings.FilterBy
import org.opensearch.OpenSearchStatusException
import org.opensearch.rest.RestStatus
import java.util.stream.Collectors

/**
 * Class for checking/filtering user access.
 */
internal object UserAccessManager {
    private const val USER_TAG = "User:"
    private const val ROLE_TAG = "Role:"
    private const val BACKEND_ROLE_TAG = "BERole:"
    private const val ALL_ACCESS_ROLE = "all_access"
    private const val OPENSEARCH_DASHBOARDS_SERVER_USER = "opensearchdashboardsserver" // TODO: Change it to background user when created.
    private const val PRIVATE_TENANT = "__user__"
    const val DEFAULT_TENANT = ""

    /**
     * Validate User if eligible to do operation
     * If filterBy == NoFilter
     *  -> No validation
     * If filterBy == User
     *  -> User name should be present
     * If filterBy == Roles
     *  -> roles should be present
     * If filterBy == BackendRoles
     *  -> backend roles should be present
     */
    fun validateUser(user: User?) {
        if (isUserPrivateTenant(user) && user?.name == null) {
            throw OpenSearchStatusException("User name not provided for private tenant access",
                RestStatus.FORBIDDEN)
        }
        when (PluginSettings.filterBy) {
            FilterBy.NoFilter -> { // No validation
            }
            FilterBy.User -> { // User name must be present
                user?.name
                    ?: throw OpenSearchStatusException("Filter-by enabled with security disabled",
                        RestStatus.FORBIDDEN)
            }
            FilterBy.Roles -> { // backend roles must be present
                if (user == null || user.roles.isNullOrEmpty()) {
                    throw OpenSearchStatusException("User doesn't have roles configured. Contact administrator.",
                        RestStatus.FORBIDDEN)
                } else if (user.roles.stream().filter { !PluginSettings.ignoredRoles.contains(it) }.count() == 0L) {
                    throw OpenSearchStatusException("No distinguishing roles configured. Contact administrator.",
                        RestStatus.FORBIDDEN)
                }
            }
            FilterBy.BackendRoles -> { // backend roles must be present
                if (user?.backendRoles.isNullOrEmpty()) {
                    throw OpenSearchStatusException("User doesn't have backend roles configured. Contact administrator.",
                        RestStatus.FORBIDDEN)
                }
            }
        }
    }

    /**
     * validate if user has access to polling actions
     */
    fun validatePollingUser(user: User?) {
        if (user != null) { // Check only if security is enabled
            if (user.name != OPENSEARCH_DASHBOARDS_SERVER_USER) {
                throw OpenSearchStatusException("Permission denied", RestStatus.FORBIDDEN)
            }
        }
    }

    /**
     * Get tenant info from user object.
     */
    fun getUserTenant(user: User?): String {
        return when (val requestedTenant = user?.requestedTenant) {
            null -> DEFAULT_TENANT
            else -> requestedTenant
        }
    }

    /**
     * Get all user access info from user object.
     */
    fun getAllAccessInfo(user: User?): List<String> {
        if (user == null) { // Security is disabled
            return listOf()
        }
        val retList: MutableList<String> = mutableListOf()
        if (user.name != null) {
            retList.add("$USER_TAG${user.name}")
        }
        user.roles.forEach { retList.add("$ROLE_TAG$it") }
        user.backendRoles.forEach { retList.add("$BACKEND_ROLE_TAG$it") }
        return retList
    }

    /**
     * Get access info for search filtering
     */
    fun getSearchAccessInfo(user: User?): List<String> {
        if (user == null) { // Security is disabled
            return listOf()
        }
        if (isUserPrivateTenant(user)) {
            return listOf("$USER_TAG${user.name}") // No sharing allowed in private tenant.
        }
        if (canAdminViewAllItems(user)) {
            return listOf()
        }
        return when (PluginSettings.filterBy) {
            FilterBy.NoFilter -> listOf()
            FilterBy.User -> listOf("$USER_TAG${user.name}")
            FilterBy.Roles -> user.roles.stream()
                .filter { !PluginSettings.ignoredRoles.contains(it) }
                .map { "$ROLE_TAG$it" }
                .collect(Collectors.toList())
            FilterBy.BackendRoles -> user.backendRoles.map { "$BACKEND_ROLE_TAG$it" }
        }
    }

    /**
     * validate if user has access based on given access list
     */
    fun doesUserHasAccess(user: User?, tenant: String, access: List<String>): Boolean {
        if (user == null) { // Security is disabled
            return true
        }
        if (getUserTenant(user) != tenant) {
            return false
        }
        if (canAdminViewAllItems(user)) {
            return true
        }
        return when (PluginSettings.filterBy) {
            FilterBy.NoFilter -> true
            FilterBy.User -> access.contains("$USER_TAG${user.name}")
            FilterBy.Roles -> user.roles.stream()
                .filter { !PluginSettings.ignoredRoles.contains(it) }
                .map { "$ROLE_TAG$it" }
                .anyMatch { it in access }
            FilterBy.BackendRoles -> user.backendRoles.map { "$BACKEND_ROLE_TAG$it" }.any { it in access }
        }
    }

    /**
     * Check if user has all info access.
     */
    fun hasAllInfoAccess(user: User?): Boolean {
        if (user == null) { // Security is disabled
            return true
        }
        return isAdminUser(user)
    }

    private fun canAdminViewAllItems(user: User): Boolean {
        return PluginSettings.adminAccess == PluginSettings.AdminAccess.AllNotebooks && isAdminUser(user)
    }

    private fun isAdminUser(user: User): Boolean {
        return user.roles.contains(ALL_ACCESS_ROLE)
    }

    private fun isUserPrivateTenant(user: User?): Boolean {
        return getUserTenant(user) == PRIVATE_TENANT
    }
}
