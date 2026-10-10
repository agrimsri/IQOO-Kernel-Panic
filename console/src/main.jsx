import { StrictMode } from "react";
import { createRoot } from "react-dom/client";
import "./styles/fonts.css";
import "./styles/tokens.css";
import "./styles/base.css";
import "./styles/components.css";
import "./styles/layout.css";
import "./styles/tour.css";
import "./styles/console.css";
import { applySavedTheme } from "./lib/theme";
import logo from "./assets/logo-96.png";
import App from "./App";

applySavedTheme();
try {
  // The guided tour starts silent; narration can be switched on from the tour card.
  if (localStorage.getItem("tour-voice") === null) localStorage.setItem("tour-voice", "off");
} catch { /* storage blocked: the tour simply stays silent for this session */ }
const icon = document.createElement("link");
icon.rel = "icon"; icon.type = "image/png"; icon.href = logo;
document.head.appendChild(icon);

createRoot(document.getElementById("root")).render(<StrictMode><App /></StrictMode>);
