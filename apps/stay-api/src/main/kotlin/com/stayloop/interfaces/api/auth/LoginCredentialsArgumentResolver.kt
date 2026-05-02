package com.stayloop.interfaces.api.auth

import com.stayloop.domain.user.value.LoginId
import com.stayloop.support.error.CoreException
import com.stayloop.support.error.ErrorType
import org.springframework.core.MethodParameter
import org.springframework.stereotype.Component
import org.springframework.web.bind.support.WebDataBinderFactory
import org.springframework.web.context.request.NativeWebRequest
import org.springframework.web.method.support.HandlerMethodArgumentResolver
import org.springframework.web.method.support.ModelAndViewContainer

@Component
class LoginCredentialsArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == LoginCredentials::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): LoginCredentials {
        val loginId = webRequest.getHeader(LoginCredentials.LOGIN_ID_HEADER)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "${LoginCredentials.LOGIN_ID_HEADER} 헤더가 필요합니다.")
        val rawPassword = webRequest.getHeader(LoginCredentials.LOGIN_PW_HEADER)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "${LoginCredentials.LOGIN_PW_HEADER} 헤더가 필요합니다.")
        return LoginCredentials(loginId = LoginId(loginId), rawPassword = rawPassword)
    }
}
