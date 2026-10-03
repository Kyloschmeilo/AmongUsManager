"""Interaktive Discord-Views (Buttons & Menüs).

Hierarchie:
    CrewView            gemeinsame Fehlerbehandlung
    ├── PersistentView  überlebt Bot-Neustarts (feste custom_ids, kein Timeout)
    └── PrivateView     darf nur von der Person bedient werden, die sie geöffnet hat
"""

from crewbot.ui.views.admin_panel import AdminPanelView
from crewbot.ui.views.control_panel import ControlPanelView
from crewbot.ui.views.event_signup import EventSignupView
from crewbot.ui.views.mvp_voting import MvpVotingView
from crewbot.ui.views.role_select import RoleSelectView

__all__ = [
    "AdminPanelView",
    "ControlPanelView",
    "EventSignupView",
    "MvpVotingView",
    "RoleSelectView",
]
