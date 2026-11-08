package dev.qqbot.security;

/** Run via scripts/password-hash.ps1; password is read from console, never a command argument. */
public final class PasswordHash {
    public static void main(String[] args){
        var console=System.console();if(console==null)throw new IllegalStateException("Run from an interactive terminal");
        char[] password=console.readPassword("Administrator password (12+ characters): ");
        try{if(password==null||password.length<12)throw new IllegalArgumentException("Use at least 12 characters");System.out.println(new org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder(12).encode(new String(password)));}
        finally{if(password!=null)java.util.Arrays.fill(password,'\0');}
    }
}
