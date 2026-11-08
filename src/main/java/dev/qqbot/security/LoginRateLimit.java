package dev.qqbot.security;
import jakarta.servlet.*;
import jakarta.servlet.http.*;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
@Component
public class LoginRateLimit extends OncePerRequestFilter {
 private record Attempt(long minute,int count){}
 private final ConcurrentHashMap<String,Attempt> attempts=new ConcurrentHashMap<>();
 @Override protected void doFilterInternal(HttpServletRequest request,HttpServletResponse response,FilterChain chain)throws ServletException,IOException{
  if(request.getRequestURI().equals("/api/admin/login")&&request.getMethod().equals("POST")){
   long minute=System.currentTimeMillis()/60000;
   if(attempts.size()>10000)attempts.entrySet().removeIf(e->e.getValue().minute()<minute);
   if(attempts.size()>=10000&&!attempts.containsKey(request.getRemoteAddr())){response.sendError(429);return;}
   var attempt=attempts.compute(request.getRemoteAddr(),(k,v)->v==null||v.minute()!=minute?new Attempt(minute,1):new Attempt(minute,v.count()+1));
   if(attempt.count()>10){response.sendError(429);return;}
  }
  chain.doFilter(request,response);
 }
}
