/**
 * Member/Auth 논리 모듈에서 로그인, 로그아웃, 서버 Session과 권한 검사를 담당합니다.
 *
 * <p>Spring Security 기반 HttpSession과 Cookie CSRF를 사용하며 JWT와 Access Token은 만들지
 * 않습니다. {@code member}와 {@code badge}는 같은 논리 모듈의 하위 역할입니다.</p>
 */
package com.gighub.auth;

