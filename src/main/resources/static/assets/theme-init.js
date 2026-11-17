(() => {
 const root = document.documentElement;
 root.classList.add('js');
 let preference = 'system';
 try { preference = localStorage.getItem('ieee-hub-theme') || 'system'; } catch (_) { /* Use system preference. */ }
 if (!['light', 'dark', 'system'].includes(preference)) preference = 'system';
 root.dataset.themePreference = preference;
 if (preference !== 'system') root.dataset.mode = preference;
})();
