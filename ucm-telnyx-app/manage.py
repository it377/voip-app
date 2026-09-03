#!/usr/bin/env python3
"""
Command-line account admin, for when you can't get in through the web UI -
a forgotten admin password being the obvious case.

Run it from this directory with the venv active:

    python manage.py list
    python manage.py passwd <username>
    python manage.py create-admin <username>
    python manage.py activate <username>

Everything here edits data/accounts.json directly, so stop the server first
if it's running (it holds the accounts file in memory and would overwrite
your change on its next write).
"""
import argparse
import getpass
import sys

from dotenv import load_dotenv

load_dotenv()

import users  # noqa: E402  (must come after load_dotenv)


def _prompt_password(username):
    first = getpass.getpass(f"New password for '{username}': ")
    if not first:
        sys.exit("Aborted: empty password.")
    second = getpass.getpass("Repeat it: ")
    if first != second:
        sys.exit("Aborted: passwords didn't match.")
    return first


def cmd_list(_args):
    all_users = users.list_users()
    if not all_users:
        print("No accounts yet. Start the server once to seed an admin, or use create-admin.")
        return
    width = max(len(u["username"]) for u in all_users)
    for user in all_users:
        flags = []
        if user["role"] == "admin":
            flags.append("admin")
        if user["canMessage"]:
            flags.append("texts")
        if not user["active"]:
            flags.append("DISABLED")
        extension = user["extension"] or "-"
        print(f"{user['username']:<{width}}  ext {extension:<6}  {', '.join(flags) or 'user'}")


def cmd_passwd(args):
    user = users.find_by_username(args.username)
    if not user:
        sys.exit(f"No such user: {args.username}")
    password = args.password or _prompt_password(args.username)
    users.update_user(user["id"], password=password)
    print(f"Password updated for '{user['username']}'.")


def cmd_create_admin(args):
    if users.find_by_username(args.username):
        sys.exit(f"User '{args.username}' already exists - use passwd to change their password.")
    password = args.password or _prompt_password(args.username)
    users.create_user(
        username=args.username,
        password=password,
        display_name=args.username,
        role="admin",
        can_message=True,
    )
    print(f"Created admin '{args.username}'.")


def cmd_activate(args):
    user = users.find_by_username(args.username)
    if not user:
        sys.exit(f"No such user: {args.username}")
    users.update_user(user["id"], active=True)
    print(f"Re-enabled '{user['username']}'.")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="show all accounts").set_defaults(func=cmd_list)

    passwd = sub.add_parser("passwd", help="set a user's password")
    passwd.add_argument("username")
    passwd.add_argument("--password", help="skip the prompt (shows up in shell history)")
    passwd.set_defaults(func=cmd_passwd)

    create = sub.add_parser("create-admin", help="create a new administrator")
    create.add_argument("username")
    create.add_argument("--password", help="skip the prompt (shows up in shell history)")
    create.set_defaults(func=cmd_create_admin)

    activate = sub.add_parser("activate", help="re-enable a disabled account")
    activate.add_argument("username")
    activate.set_defaults(func=cmd_activate)

    args = parser.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
