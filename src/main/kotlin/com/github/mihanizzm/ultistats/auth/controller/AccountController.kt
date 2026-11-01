package com.github.mihanizzm.ultistats.auth.controller

import com.github.mihanizzm.ultistats.auth.model.*
import com.github.mihanizzm.ultistats.auth.service.AuthService
import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/v1/accounts")
class AccountController(private val service:AuthService) {
    @GetMapping fun list(@RequestParam(defaultValue="0") page:Int,@RequestParam(defaultValue="20") size:Int,
        @RequestParam(required=false) search:String?)=service.listAccounts(page,size,search)
    @PostMapping @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody body:CreateAccountRequest,request:HttpServletRequest)=service.createAccount(principal(request),body)
    @PatchMapping("/{id}") fun update(@PathVariable id:UUID,@RequestBody body:UpdateAccountRequest,request:HttpServletRequest)=
        service.updateAccount(principal(request),id,body)
    @PostMapping("/{id}/password-reset") fun reset(@PathVariable id:UUID,@RequestBody body:Map<String,Any?>,request:HttpServletRequest):TemporaryAccountResult {
        require(body.isEmpty()) { "Password reset body must be empty" }
        return service.resetPassword(principal(request),id)
    }
    private fun principal(request:HttpServletRequest)=request.getAttribute(AuthPrincipal::class.java.name) as AuthPrincipal
}
