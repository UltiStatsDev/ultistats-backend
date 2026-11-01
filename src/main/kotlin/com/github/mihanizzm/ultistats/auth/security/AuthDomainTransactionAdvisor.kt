package com.github.mihanizzm.ultistats.auth.security

import com.github.mihanizzm.ultistats.auth.model.AuthPermission
import com.github.mihanizzm.ultistats.auth.repository.AuthTransactionFence
import org.aopalliance.intercept.MethodInterceptor
import org.springframework.aop.support.DefaultPointcutAdvisor
import org.springframework.aop.support.StaticMethodMatcherPointcut
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource
import org.springframework.transaction.support.TransactionSynchronizationManager
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes
import java.lang.reflect.Method

/** Runs inside transaction advice (order 0), before any protected method effects. */
class AuthDomainTransactionAdvisor(fence:AuthTransactionFence,access:AuthRequestAccess):DefaultPointcutAdvisor() {
    init {
        order=1
        val attributes=AnnotationTransactionAttributeSource()
        setPointcut(object:StaticMethodMatcherPointcut() {
            override fun matches(method:Method,targetClass:Class<*>):Boolean {
                val name=targetClass.name
                val domain=listOf(".service.",".facade.",".transfer.",".obs.").any(name::contains)
                return domain && !name.contains(".auth.") && attributes.getTransactionAttribute(method,targetClass)!=null
            }
        })
        advice=MethodInterceptor { invocation ->
            val request=(RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
            val requirement=request?.getAttribute(AuthRequirement::class.java.name) as? AuthRequirement.Permission
            if(request!=null && requirement!=null && requirement.permission!=AuthPermission.ACCOUNT_ADMIN && DefaultAuthRequestAccess.unsafe(request)) {
                check(TransactionSynchronizationManager.isActualTransactionActive()) { "Transaction advice must precede authority advice" }
                if(!TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
                    fence.acquire(false)
                    try { access.requirePermission(request,requirement.permission) } // Fresh DB authority after waiting.
                    catch(problem:AuthProblem) { throw AuthProblem(problem.status,problem.code,problem.retryAfterSeconds,true) }
                }
            }
            invocation.proceed()
        }
    }
}
