(() => {
  const toggle = document.querySelector(".site-menu-toggle");
  const sidebar = document.getElementById("site-sidebar");

  if (!toggle || !sidebar) return;

  document.body.append(toggle, sidebar);

  const setExpanded = (expanded) => {
    document.body.classList.toggle("site-sidebar-open", expanded);
    toggle.setAttribute("aria-expanded", String(expanded));
    sidebar.inert = !expanded;
    sidebar.setAttribute("aria-hidden", String(!expanded));
  };

  toggle.addEventListener("click", () => {
    setExpanded(toggle.getAttribute("aria-expanded") !== "true");
  });

  document.addEventListener("click", (event) => {
    if (!document.body.classList.contains("site-sidebar-open")) return;
    if (sidebar.contains(event.target) || toggle.contains(event.target)) return;
    setExpanded(false);
  });

  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape") setExpanded(false);
  });
})();
