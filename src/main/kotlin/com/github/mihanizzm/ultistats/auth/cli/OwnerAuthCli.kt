package com.github.mihanizzm.ultistats.auth.cli

import com.github.mihanizzm.ultistats.auth.service.AuthService
import org.springframework.stereotype.Component

data class OwnerCommand(val operation:String,val login:String,val displayName:String?,val stdin:Boolean)

@Component
class OwnerAuthCli(private val service:AuthService) {
    fun execute(command:OwnerCommand,input:()->CharArray,output:(String)->Unit) {
        val secret=input()
        try {
            val account=when(command.operation) {
                "bootstrap" -> service.ownerBootstrap(command.login,command.displayName!!,String(secret))
                "recover" -> service.ownerRecover(command.login,String(secret))
                else -> error("Unsupported owner command")
            }
            output("Owner account ${account.id}; temporary password change required.")
        } finally { secret.fill('\u0000') }
    }
    companion object {
        fun parse(args:List<String>):OwnerCommand {
            require(args.size>=2 && args[0]=="owner" && args[1] in setOf("bootstrap","recover")) { "Invalid owner command" }
            val options=linkedMapOf<String,String>()
            var stdin=false
            args.drop(2).forEach { value ->
                if(value=="--stdin") { require(!stdin) { "Duplicate owner option" };stdin=true }
                else {
                    val key=value.substringBefore('=')
                    require(key in setOf("--login","--display-name") && value.contains('=')) { "Unsupported owner option; passwords are input only" }
                    require(options.put(key,value.substringAfter('='))==null) { "Duplicate owner option" }
                }
            }
            val login=options["--login"] ?: throw IllegalArgumentException("Owner login required")
            val display=options["--display-name"]
            require(args[1]!="bootstrap" || display!=null) { "Bootstrap display name required" }
            require(args[1]!="recover" || display==null) { "Recovery does not rename the account" }
            return OwnerCommand(args[1],login,display,stdin)
        }
        fun readPassword(command:OwnerCommand):CharArray {
            if(command.stdin) return System.`in`.bufferedReader().readLine()?.toCharArray()
                ?: throw IllegalArgumentException("Password input required")
            val console=System.console() ?: throw IllegalArgumentException("Hidden console required; use explicit --stdin for local automation")
            return console.readPassword("Temporary owner password: ") ?: throw IllegalArgumentException("Password input required")
        }
    }
}
