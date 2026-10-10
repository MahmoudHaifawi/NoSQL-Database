package com.database.atypon.Node.services.authentication;

import com.database.atypon.Node.model.User;
import com.database.atypon.Node.security.JwtService;
import com.database.atypon.Node.utils.PathBuilder;
import com.database.atypon.Node.utils.file_operations.fileReader.FileReader;
import com.database.atypon.Node.utils.response.Response;
import com.database.atypon.Node.utils.response.ResponseType;
import org.json.JSONObject;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import javax.servlet.http.Cookie;
import java.io.File;
import java.time.Duration;

@Service
public class AuthenticationService {

    private final BCryptPasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthenticationService(BCryptPasswordEncoder passwordEncoder, JwtService jwtService) {
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    public Response authenticateUser(User user) {
        String pathToInfo = PathBuilder.getPathToMainInfo();

        try{
            FileReader fileReader = new FileReader(new File(pathToInfo));
            fileReader.read();
            JSONObject jsonObject = new JSONObject(fileReader.getContent());

            JSONObject users = jsonObject.getJSONObject("users");
            JSONObject userObject = users.getJSONObject(user.getUsername());
            if (userObject == null)
                return new Response(ResponseType.ERROR, "User not found");
            if (!passwordEncoder.matches(user.getPassword(), userObject.getString("password")))
                return new Response(ResponseType.ERROR, "Wrong password");
            user.setRole(userObject.getString("role"));
            return new Response(ResponseType.SUCCESS, "Login successful", user);
        }catch (Exception e){
            return new Response(ResponseType.ERROR, "User not found");
        }
    }
    public Cookie buildTokenCookie(String token){
        Cookie cookie = new Cookie("token", token);
        cookie.setHttpOnly(true);
        cookie.setMaxAge(60*60);
        return cookie;
    }
    public String generateToken(User user) throws Exception{
        if(user.getRole() == null)
            throw new Exception("User role is null");

        // role is stored lower-case in info.json ("admin"/"user"); JWT claims use upper-case.
        return jwtService.generateToken(user.getUsername(), user.getRole().toUpperCase(), Duration.ofHours(1));
    }
}
