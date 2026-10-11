package com.patbaumgartner.stringtelephone

import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Controller
import org.springframework.ui.Model
import org.springframework.web.bind.annotation.*

data class AdminCreateRequest(val username: String, val displayName: String, val sipPassword: String? = null)
data class ResetRequest(val username: String, val sipPassword: String? = null)
data class EnabledRequest(val username: String, val enabled: Boolean)

@RestController
@RequestMapping("/v1/admin")
class AdminApi(private val accounts: AccountService, private val pbx: PbxGateway) {
    @GetMapping("/list-users")
    fun list() = mapOf("users" to accounts.list())

    @PostMapping("/create-user")
    fun create(@RequestBody body: AdminCreateRequest): ResponseEntity<AccountCredentials> {
        val result = accounts.create(body.username, body.displayName, body.sipPassword)
        return ResponseEntity.status(if (result.ready) HttpStatus.CREATED else HttpStatus.ACCEPTED).body(result)
    }

    @PostMapping("/reset-user")
    fun reset(@RequestBody body: ResetRequest) = accounts.reset(body.username, body.sipPassword)

    @PostMapping("/revoke-user")
    fun revoke(@RequestBody body: RevokeRequest): RevokeResponse {
        accounts.enabled(body.username, false)
        return RevokeResponse(body.username, true)
    }

    @PostMapping("/set-enabled")
    fun enabled(@RequestBody body: EnabledRequest): Map<String, Boolean> {
        accounts.enabled(body.username, body.enabled)
        return mapOf("enabled" to body.enabled)
    }

    @GetMapping("/pbx")
    fun status() = mapOf("sync" to accounts.syncStatus, "settings" to accounts.settings(), "status" to pbx.status())

    @PostMapping("/pbx/settings")
    fun settings(@RequestBody settings: PbxSettings): SyncStatus {
        accounts.settings(settings)
        return accounts.syncStatus
    }

    @PostMapping("/pbx/reconcile")
    fun reconcile(): SyncStatus {
        accounts.reconcile()
        if (!accounts.syncStatus.ready) throw PbxUnavailable(accounts.syncStatus.error.orEmpty())
        return accounts.syncStatus
    }
}

@Controller
class AdminPages(private val accounts: AccountService, private val pbx: PbxGateway) {
    @GetMapping("/")
    fun root() = "redirect:/admin/"

    @GetMapping("/admin/login")
    fun login() = "login"

    @GetMapping("/admin", "/admin/")
    fun home(model: Model): String = render(model)

    @PostMapping("/admin/accounts")
    fun create(
        @RequestParam username: String,
        @RequestParam displayName: String,
        @RequestParam(defaultValue = "") sipPassword: String,
        model: Model,
        response: HttpServletResponse,
    ): String = action(model, response) {
        val credential = accounts.create(username, displayName, sipPassword)
        model.addAttribute("credential", credential)
        model.addAttribute("notice", if (credential.ready) "Account created and PBX configured." else "Account saved but not ready. Save these credentials now, then resolve the PBX error and retry reconciliation.")
    }

    @PostMapping("/admin/accounts/{username}/reset")
    fun reset(@PathVariable username: String, @RequestParam(defaultValue = "") sipPassword: String, model: Model, response: HttpServletResponse): String =
        action(model, response) {
            model.addAttribute("credential", accounts.reset(username, sipPassword))
            model.addAttribute("notice", "Credentials replaced. Previous API credentials no longer work; save the new credentials below. PBX status is shown separately.")
        }

    @PostMapping("/admin/accounts/{username}/enabled")
    fun enabled(@PathVariable username: String, @RequestParam enabled: Boolean, model: Model, response: HttpServletResponse): String =
        action(model, response) {
            accounts.enabled(username, enabled)
            model.addAttribute("notice", if (enabled) "Account enabled and PBX configured." else "Account disabled. API sessions, SIP access and active calls have been disconnected.")
        }

    @PostMapping("/admin/accounts/{username}/name")
    fun rename(@PathVariable username: String, @RequestParam displayName: String, model: Model, response: HttpServletResponse): String =
        action(model, response) {
            accounts.rename(username, displayName)
            model.addAttribute("notice", "Display name updated.")
        }

    @PostMapping("/admin/pbx/settings")
    fun settings(
        @RequestParam codecs: String,
        @RequestParam ringSeconds: Int,
        @RequestParam maxContacts: Int,
        @RequestParam(defaultValue = "") externalAddress: String,
        @RequestParam(defaultValue = "") localNetwork: String,
        @RequestParam(defaultValue = "false") symmetricMedia: Boolean,
        model: Model,
        response: HttpServletResponse,
    ): String = action(model, response) {
        accounts.settings(PbxSettings(codecs, ringSeconds, maxContacts, externalAddress, localNetwork, symmetricMedia))
        model.addAttribute("notice", "PBX settings saved, applied and verified.")
    }

    @PostMapping("/admin/pbx/reconcile")
    fun reconcile(model: Model, response: HttpServletResponse): String = action(model, response) {
        accounts.reconcile()
        if (!accounts.syncStatus.ready) throw PbxUnavailable(accounts.syncStatus.error.orEmpty())
        model.addAttribute("notice", "PBX configuration reconciled and verified.")
    }

    private fun action(model: Model, response: HttpServletResponse, block: () -> Unit): String {
        try {
            block()
        } catch (error: IllegalArgumentException) {
            response.status = 400
            model.addAttribute("error", error.message)
        } catch (error: ApiFailure) {
            response.status = error.status.value()
            model.addAttribute("error", error.message)
        } catch (error: PbxUnavailable) {
            response.status = 503
            model.addAttribute("error", error.message)
        }
        return render(model)
    }

    private fun render(model: Model): String {
        model.addAttribute("accounts", accounts.list())
        model.addAttribute("settings", accounts.settings())
        model.addAttribute("sync", accounts.syncStatus)
        try {
            model.addAttribute("pbx", pbx.status())
        } catch (error: PbxUnavailable) {
            model.addAttribute("pbxError", error.message)
        }
        return "admin"
    }
}
