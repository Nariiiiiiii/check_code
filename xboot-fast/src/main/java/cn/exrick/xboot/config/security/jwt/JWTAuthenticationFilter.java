package cn.exrick.xboot.config.security.jwt;

import cn.exrick.xboot.common.constant.SecurityConstant;
import cn.exrick.xboot.common.redis.RedisTemplateHelper;
import cn.exrick.xboot.common.utils.ResponseUtil;
import cn.exrick.xboot.common.utils.SecurityUtil;
import cn.exrick.xboot.common.vo.TokenUser;
import cn.exrick.xboot.config.properties.XbootTokenProperties;
import cn.hutool.core.util.StrUtil;
import com.google.gson.Gson;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.ExpiredJwtException;
import io.jsonwebtoken.Jwts;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.authentication.www.BasicAuthenticationFilter;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * @author Exrickx
 */
@Slf4j
public class JWTAuthenticationFilter extends BasicAuthenticationFilter {

    private XbootTokenProperties tokenProperties;

    private RedisTemplateHelper redisTemplate;

    private SecurityUtil securityUtil;

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager,
                                   XbootTokenProperties tokenProperties,
                                   RedisTemplateHelper redisTemplate, SecurityUtil securityUtil) {
        super(authenticationManager);
        this.tokenProperties = tokenProperties;
        this.redisTemplate = redisTemplate;
        this.securityUtil = securityUtil;
    }

    public JWTAuthenticationFilter(AuthenticationManager authenticationManager, AuthenticationEntryPoint authenticationEntryPoint) {
        super(authenticationManager, authenticationEntryPoint);
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws IOException, ServletException {

        String header = request.getHeader(SecurityConstant.HEADER);
        if (StrUtil.isBlank(header)) {
            header = request.getParameter(SecurityConstant.HEADER);
        }
        Boolean notValid = StrUtil.isBlank(header) || (!tokenProperties.getRedis() && !header.startsWith(SecurityConstant.TOKEN_SPLIT));
        if (notValid) {
            chain.doFilter(request, response);
            return;
        }
        try {
            UsernamePasswordAuthenticationToken authentication = null;
            if (StrUtil.isNotBlank(header)) {
                authentication = getAuthentication(header, response);
            }
            if (authentication == null) {
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(authentication);
        } catch (Exception e) {
            log.warn(e.toString());
        }

        chain.doFilter(request, response);
    }

    private UsernamePasswordAuthenticationToken getAuthentication(String header, HttpServletResponse response) {

        // ?????????
        String username = null;
        // ??????
        List<GrantedAuthority> authorities = new ArrayList<>();

        if (tokenProperties.getRedis()) {
            // redis
            String v = redisTemplate.get(SecurityConstant.TOKEN_PRE + header);
            if (StrUtil.isBlank(v)) {
                ResponseUtil.out(response, ResponseUtil.resultMap(false, 401, "?????????????????????????????????"));
                return null;
            }
            TokenUser user = new Gson().fromJson(v, TokenUser.class);
            username = user.getUsername();
            if (tokenProperties.getStorePerms()) {
                // ???????????????
                for (String ga : user.getPermissions()) {
                    authorities.add(new SimpleGrantedAuthority(ga));
                }
            } else {
                // ????????? ??????????????????
                authorities = securityUtil.getCurrUserPerms(username);
            }
            if (!user.getSaveLogin()) {
                // ????????????????????????????????????????????????
                redisTemplate.set(SecurityConstant.USER_TOKEN + username, header, tokenProperties.getTokenExpireTime(), TimeUnit.MINUTES);
                redisTemplate.set(SecurityConstant.TOKEN_PRE + header, v, tokenProperties.getTokenExpireTime(), TimeUnit.MINUTES);
            }
        } else {
            // JWT
            try {
                // ??????token
                Claims claims = Jwts.parser()
                        .setSigningKey(SecurityConstant.JWT_SIGN_KEY)
                        .parseClaimsJws(header.replace(SecurityConstant.TOKEN_SPLIT, ""))
                        .getBody();

                // ???????????????
                username = claims.getSubject();
                // JWT??????????????? ?????????????????? ??????JWT????????????
                authorities = securityUtil.getCurrUserPerms(username);
            } catch (ExpiredJwtException e) {
                ResponseUtil.out(response, ResponseUtil.resultMap(false, 401, "?????????????????????????????????"));
            } catch (Exception e) {
                log.error(e.toString());
                ResponseUtil.out(response, ResponseUtil.resultMap(false, 500, "??????token??????"));
            }
        }

        if (StrUtil.isNotBlank(username)) {
            // ???????????? ??????password?????????null
            User principal = new User(username, "", authorities);
            return new UsernamePasswordAuthenticationToken(principal, null, authorities);
        }
        return null;
    }
}

