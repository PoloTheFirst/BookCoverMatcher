// Simple online search stub implementation.
// In a real app this would call an external API (e.g., Google Custom Search,
// Bing Image Search) passing the scanned image or its hash and return shopping links.
// For now we generate placeholder results to demonstrate UI integration.

/**
 * Render a list of online search result items into the #onlineResults element.
 * Each item should have {title, url, thumb} properties.
 */
export function renderOnlineResults(list) {
  const container = document.getElementById('onlineResults');
  if (!container) return;
  // Clear existing content
  container.innerHTML = '';
  if (!list || !list.length) {
    const li = document.createElement('li');
    li.className = 'empty';
    li.textContent = 'No online results yet.';
    container.appendChild(li);
    return;
  }

  list.forEach((item, idx) => {
    const li = document.createElement('li');
    li.className = 'online-result';
    // Simple markup: thumbnail image and link
    li.innerHTML = `
      <div class="rank">#${idx + 1}</div>
      <img class="thumb" src="${item.thumb}" alt="Result ${idx + 1}">
      <a href="${item.url}" target="_blank" rel="noopener noreferrer">${item.title}</a>`;
    container.appendChild(li);
  });
}

/**
 * Perform a fake online search based on the provided image data URL.
 * Returns a Promise that resolves to an array of result objects.
 */
export async function performOnlineSearch(imageDataUrl) {
  // Placeholder: simulate network latency
  await new Promise(r => setTimeout(r, 500));

  // Generate dummy results using the image data URL as thumbnail.
  const dummy = [];
  for (let i = 1; i <= 5; i++) {
    dummy.push({
      title: `Sample Product ${i}`,
      url: `https://example.com/product/${i}`,
      thumb: imageDataUrl, // reuse captured thumbnail
    });
  }
  return dummy;
}
