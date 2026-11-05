const form = document.querySelector("#login-form");
const feedback = document.querySelector("#login-feedback");
const button = document.querySelector("#login-button");

async function checkSession() {
  try {
    const response = await fetch("/api/auth/session", { cache: "no-store" });
    const session = await response.json();
    if (session.authenticated) window.location.replace("/");
  } catch {
    feedback.textContent = "Không kết nối được local host.";
  }
}

form.addEventListener("submit", async (event) => {
  event.preventDefault();
  feedback.textContent = "";
  button.disabled = true;
  try {
    const response = await fetch("/api/auth/login", {
      method: "POST",
      headers: { "Content-Type": "application/json" },
      body: JSON.stringify({
        username: document.querySelector("#username").value.trim(),
        password: document.querySelector("#password").value,
      }),
    });
    const result = await response.json().catch(() => ({}));
    if (!response.ok) throw new Error(result.error ?? `HTTP ${response.status}`);
    window.location.replace("/");
  } catch (error) {
    feedback.textContent = error.message;
    document.querySelector("#password").value = "";
    document.querySelector("#password").focus();
  } finally {
    button.disabled = false;
  }
});

await checkSession();
