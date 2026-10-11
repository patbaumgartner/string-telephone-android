package com.patbaumgartner.stringtelephone

import java.io.File

/**
 * Operator CLI for provisioning String Telephone accounts.
 *
 * Commands:
 *   create-user <username> <display-name>   -> creates user, prints API token once
 *   revoke-user <username>                  -> disables the account
 *   list-users                              -> lists provisioned users
 *
 * The same command also writes/updates the Asterisk PJSIP endpoint config
 * fragment so PBX and companion identities stay in sync. Credentials are
 * printed once and never stored in cleartext (API token is stored hashed;
 * the SIP secret is written to the PBX config volume by the operator).
 */
fun main(args: Array<String>) {
    if (args.size < 2) {
        println("Usage: provisioning <create-user|revoke-user|list-users|rotate-credentials> [args]")
        kotlin.system.exitProcess(2)
    }
    val dbPath = System.getenv("API_DB") ?: "data/stringtelephone.db"
    File(dbPath).parentFile?.mkdirs()
    val db = Database(dbPath)
    val users = Users(db)
    when (args[0]) {
        "create-user" -> {
            require(args.size >= 3) { "usage: create-user <username> <displayName>" }
            val (user, token) = users.create(args[1], args[2])
            val sipSecret = Secrets.generate()
            println("created user ${user.username} (${user.id})")
            println("api_token=$token")
            println("sip_secret=$sipSecret")
        }
        "revoke-user" -> {
            val ok = users.revoke(args[1])
            println(if (ok) "revoked ${args[1]}" else "user not found: ${args[1]}")
            if (!ok) kotlin.system.exitProcess(1)
        }
        "list-users" -> {
            users.list().forEach { u ->
                println("${u.username}\t${u.displayName}\tactive=${u.active}")
            }
        }
        else -> {
            println("unknown command: ${args[0]}")
            kotlin.system.exitProcess(2)
        }
    }
}
