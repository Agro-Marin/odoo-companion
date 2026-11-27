import os

from odoo import Command

identifier = os.environ["LAB_IDENTIFIER"]
token_file = os.environ["LAB_TOKEN_FILE"]

agent = env["res.users"].search([("login", "=", f"lab_{identifier.lower()}")]) or env[
    "res.users"
].create({"name": "Lab handset holder", "login": f"lab_{identifier.lower()}"})
settings = env["res.users.settings"]._get_or_create_for_user(agent)
settings.write({"voip_username": "7201", "voip_secret": "lab-7201"})
settings.voip_provider_id.write({"pbx_ip": os.environ["LAB_PBX"]})

phone = env["device.device"].search([("identifier", "=", identifier)])
if not phone:
    profile = env["device.profile"].create(
        {"name": "Lab handset", "auth_type": "bearer", "auth_token": "lab"}
    )
    phone = env["device.device"].create(
        {
            "name": "Lab handset",
            "identifier": identifier,
            "config_id": profile.id,
            "comm_protocol": "http",
            "endpoint": "10.0.0.1",
            "capability_ids": [
                Command.link(env.ref("device_mobile.capability_companion").id)
            ],
        }
    )
if not phone.resource_id:
    phone.action_register_equipment()
phone.operator_id = agent.partner_id._get_or_create_resources(
    phone.company_id or agent.company_id
)
env.cr.commit()

with open(token_file, "w") as out:
    out.write(phone.credential_id.sudo().credential_value)
print("lab handset", identifier, "ready:", phone._resolve_softphone_settings() is not None)
