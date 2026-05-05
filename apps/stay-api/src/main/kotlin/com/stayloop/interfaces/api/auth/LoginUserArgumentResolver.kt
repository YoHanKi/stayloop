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
class LoginUserArgumentResolver : HandlerMethodArgumentResolver {
    override fun supportsParameter(parameter: MethodParameter): Boolean =
        parameter.parameterType == LoginUser::class.java

    override fun resolveArgument(
        parameter: MethodParameter,
        mavContainer: ModelAndViewContainer?,
        webRequest: NativeWebRequest,
        binderFactory: WebDataBinderFactory?,
    ): LoginUser {
        val raw = webRequest.getHeader(LoginUser.LOGIN_ID_HEADER)
            ?: throw CoreException(ErrorType.UNAUTHORIZED, "${LoginUser.LOGIN_ID_HEADER} 헤더가 필요합니다.")
        return LoginUser(loginId = LoginId(raw))
    }
}
