const API_URL = '/api';

// --- Auth Handling ---
async function handleLogin(e) {
    if(e) e.preventDefault();
    const email = document.getElementById('loginEmail').value;
    const pass = document.getElementById('loginPassword').value;
    try {
        const res = await fetch(`${API_URL}/auth/login`, {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: `email=${encodeURIComponent(email)}&password=${encodeURIComponent(pass)}`
        });
        const data = await res.json();
        if (data.success) {
            localStorage.setItem('token', data.token);
            localStorage.setItem('name', data.name);
            localStorage.setItem('username', data.username);
            window.location.href = '/dashboard.html';
        } else {
            document.getElementById('loginError').innerText = data.message || "Invalid credentials.";
        }
    } catch(err) {
        document.getElementById('loginError').innerText = "An error occurred connecting to the server.";
    }
}

async function handleRegister(e) {
    if(e) e.preventDefault();
    const name = document.getElementById('regName').value;
    const username = document.getElementById('regUsername').value;
    const email = document.getElementById('regEmail').value;
    const pass = document.getElementById('regPassword').value;
    try {
        const res = await fetch(`${API_URL}/auth/register`, {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: `name=${encodeURIComponent(name)}&username=${encodeURIComponent(username)}&email=${encodeURIComponent(email)}&password=${encodeURIComponent(pass)}`
        });
        const data = await res.json();
        if (data.success && data.token) {
            localStorage.setItem('token', data.token);
            localStorage.setItem('name', data.name);
            localStorage.setItem('username', data.username);
            window.location.href = '/dashboard.html';
        } else if (data.success) {
            switchTab('login');
            document.getElementById('loginEmail').value = email;
            alert("Account created! Please log in.");
        } else {
            document.getElementById('regError').innerText = data.message || "Email already exists.";
        }
    } catch(err) {
        document.getElementById('regError').innerText = "An error occurred.";
    }
}

async function updateUsername() {
    const newUsername = document.getElementById('profileUsernameDisplay').value;
    const status = document.getElementById('usernameStatus');
    
    if (!newUsername || !newUsername.trim()) {
        status.innerText = "Username cannot be empty";
        status.className = "error-msg";
        status.style.color = "var(--danger)";
        status.style.display = "block";
        return;
    }

    const data = await fetchApi('/auth/update-username', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `username=${encodeURIComponent(newUsername.trim())}`
    });

    status.style.display = "block";
    if (data.success) {
        localStorage.setItem('username', data.username);
        status.innerText = "Username updated successfully!";
        status.style.color = "#4ade80";
        setTimeout(() => { status.style.display = 'none'; }, 3000);
    } else {
        status.innerText = data.message || "Update failed";
        status.style.color = "var(--danger)";
    }
}

async function logout() {
    await fetch(`${API_URL}/auth/logout`, {
        method: 'POST',
        headers: {'X-Token': localStorage.getItem('token')}
    });
    localStorage.removeItem('token');
    localStorage.removeItem('name');
    window.location.href = '/index.html';
}

function openLogoutModal() {
    document.getElementById('logoutModal').classList.add('active');
}

// --- Navigation ---
function showView(view) {
    document.querySelectorAll('.nav-links a').forEach(a => a.classList.remove('active'));
    document.getElementById('nav-' + view).classList.add('active');
    
    document.getElementById('dashboardView').classList.add('hidden');
    document.getElementById('documentsView').classList.add('hidden');
    const teamsView = document.getElementById('teamsView');
    if (teamsView) teamsView.classList.add('hidden');
    const profileView = document.getElementById('profileView');
    if (profileView) profileView.classList.add('hidden');
    
    document.getElementById(view + 'View').classList.remove('hidden');
    
    if (view === 'dashboard') loadDashboard();
    if (view === 'documents') loadDocuments();
    if (view === 'teams') loadTeams();
}

// --- Data Loading ---
async function fetchApi(endpoint, options = {}) {
    options.headers = options.headers || {};
    options.headers['X-Token'] = localStorage.getItem('token');
    const res = await fetch(`${API_URL}${endpoint}`, options);
    if(res.status === 401) logout();
    return res.json();
}

async function loadDashboard() {
    const data = await fetchApi('/docs/dashboard');
    if (data.success) {
        if (data.user) {
            const profileName = document.getElementById('profileNameDisplay');
            const profileEmail = document.getElementById('profileEmailDisplay');
            const profileUsername = document.getElementById('profileUsernameDisplay');
            if (profileName) profileName.value = data.user.name;
            if (profileEmail) profileEmail.value = data.user.email;
            if (profileUsername) profileUsername.value = localStorage.getItem('username') || '';
        }
        document.getElementById('statTotalDocs').innerText = data.totalDocs;
        document.getElementById('statTotalVersions').innerText = data.totalVersions;
        const list = document.getElementById('activityList');
        list.innerHTML = '';
        if (data.activity.length === 0) {
            list.innerHTML = '<li>No recent activity</li>';
        } else {
            data.activity.forEach(a => {
                list.innerHTML += `<li>${a.action} <div class="activity-time">${a.timestamp}</div></li>`;
            });
        }
    }
}

let allDocsData = []; // Store all docs

async function loadDocuments() {
    const data = await fetchApi('/docs/list');
    const tbody = document.getElementById('docsTableBody');
    tbody.innerHTML = '';
    
    if (data.success) {
        allDocsData = data.data;
        filterDocs(); // Initial render
    }
}

function filterDocs() {
    const tbody = document.getElementById('docsTableBody');
    tbody.innerHTML = '';
    
    let filtered = [...allDocsData];
    const search = document.getElementById('searchInput').value.toLowerCase();
    
    if (search) {
        filtered = filtered.filter(d => d.name.toLowerCase().includes(search));
    }
    
    const sortBy = document.getElementById('sortSelect').value;
    if (sortBy === 'latest') {
        filtered.sort((a, b) => new Date(b.created_at) - new Date(a.created_at));
    } else {
        filtered.sort((a, b) => new Date(a.created_at) - new Date(b.created_at));
    }
    
    if (filtered.length === 0) {
        tbody.innerHTML = `<tr><td colspan="5" style="text-align:center;">No documents found. Start by uploading one!</td></tr>`;
        return;
    }
    
    filtered.forEach(d => {
        let actionsHtml = `
            <button onclick="openUploadModal(${d.id}, '${d.name.replace(/'/g, "\\'")}')">New Ver</button>
            <button onclick="openHistory(${d.id}, '${d.name.replace(/'/g, "\\'")}')">History</button>
        `;
        if (!d.is_shared) {
            actionsHtml += `
                <button onclick="openShareModal(${d.id})">Share</button>
                <button onclick="openRenameModal(${d.id}, '${d.name.replace(/'/g, "\\'")}')">Rename</button>
            `;
        } else {
            actionsHtml += `<span class="badge" style="margin-left: 0.5rem;">Shared</span>`;
        }

        actionsHtml += `
            <div class="action-menu-container" style="position: relative; display: inline-flex; vertical-align: middle; margin-left: 0.5rem;">
                <button onclick="toggleActionMenu(event, ${d.id})" class="icon-btn" title="More Actions">
                    <svg class="menu-toggle-icon" id="menu-icon-${d.id}" viewBox="0 0 32 32">
                        <path class="menu-toggle-path-1" d="M27 10 13 10C10.8 10 9 8.2 9 6 9 3.5 10.8 2 13 2 15.2 2 17 3.8 17 6L17 26C17 28.2 18.8 30 21 30 23.2 30 25 28.2 25 26 25 23.8 23.2 22 21 22L7 22" />
                        <path d="M7 16 27 16" />
                    </svg>
                </button>
                <div id="action-dropdown-${d.id}" class="action-dropdown">
                    <button class="dropdown-item" onclick="downloadVersion(${d.last_vid}); toggleActionMenu(event, ${d.id})">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" x2="12" y1="15" y2="3"/></svg>
                        Download
                    </button>
                    ${!d.is_shared ? `
                    <button class="dropdown-item danger" onclick="deleteDoc(${d.id}); toggleActionMenu(event, ${d.id})">
                        <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M3 6h18"/><path d="M19 6v14c0 1-1 2-2 2H7c-1 0-2-1-2-2V6"/><path d="M8 6V4c0-1 1-2 2-2h4c1 0 2 1 2 2v2"/></svg>
                        Delete
                    </button>
                    ` : ''}
                </div>
            </div>
        `;

        tbody.innerHTML += `
            <tr>
                <td>#${d.id}</td>
                <td style="font-weight: 500;">${d.name}</td>
                <td><span style="background:var(--border-color); padding: 2px 8px; border-radius: 20px;">v${d.version_count}</span></td>
                <td>${d.created_at.split(' ')[0]}</td>
                <td class="actions">
                    ${actionsHtml}
                </td>
            </tr>
        `;
    });
}

// --- Action Menu Toggle ---
function toggleActionMenu(event, id) {
    event.stopPropagation();
    const dropdown = document.getElementById(`action-dropdown-${id}`);
    const icon = document.getElementById(`menu-icon-${id}`);
    
    // Close other open menus
    document.querySelectorAll('.action-dropdown.active').forEach(d => {
        if (d.id !== `action-dropdown-${id}`) {
            d.classList.remove('active');
            const otherIcon = document.getElementById(`menu-icon-${d.id.split('-').pop()}`);
            if (otherIcon) otherIcon.classList.remove('open');
        }
    });
    
    const isOpen = dropdown.classList.toggle('active');
    icon.classList.toggle('open', isOpen);
}

// Global click listener to close action menus
document.addEventListener('click', (e) => {
    if (!e.target.closest('.action-menu-container')) {
        document.querySelectorAll('.action-dropdown.active').forEach(d => {
            d.classList.remove('active');
            const otherIcon = document.getElementById(`menu-icon-${d.id.split('-').pop()}`);
            if (otherIcon) otherIcon.classList.remove('open');
        });
    }
});

// --- Actions & Modals ---
function closeModal(id) {
    document.getElementById(id).classList.remove('active');
}

function openUploadModal(docId = null, docName = null) {
    document.getElementById('uploadForm').reset();
    document.getElementById('uploadDocId').value = docId || '';
    if (docId) {
        document.getElementById('uploadModalTitle').innerText = 'Upload New Version';
        document.getElementById('renameInfoMsg').innerHTML = `Uploading as new version for <b>${docName}</b>`;
        document.getElementById('renameInfoMsg').classList.remove('hidden');
    } else {
        document.getElementById('uploadModalTitle').innerText = 'Upload New Document';
        document.getElementById('renameInfoMsg').classList.add('hidden');
    }
    document.getElementById('uploadModal').classList.add('active');
}

async function handleUpload(e) {
    e.preventDefault();
    const fileInput = document.getElementById('uploadFile');
    if (fileInput.files.length === 0) return;
    
    const file = fileInput.files[0];
    const docId = document.getElementById('uploadDocId').value;
    const notes = document.getElementById('uploadNotes').value;
    
    const headers = {
        'X-Token': localStorage.getItem('token'),
        'X-File-Name': encodeURIComponent(file.name),
        'X-Notes': encodeURIComponent(notes)
    };
    if (docId) headers['X-Document-Id'] = docId;

    const btn = e.target.querySelector('button');
    const oldText = btn.innerText;
    btn.innerText = "Uploading...";
    btn.disabled = true;

    try {
        const res = await fetch(`${API_URL}/docs/upload`, {
            method: 'POST',
            headers: headers,
            body: file
        });
        const data = await res.json();
        if (data.success) {
            closeModal('uploadModal');
            loadDocuments();
            loadDashboard();
        } else {
            alert("Upload failed.");
        }
    } catch(err) {
        alert("Upload error.");
    }
    btn.innerText = oldText;
    btn.disabled = false;
}

function openRenameModal(id, currentName) {
    document.getElementById('renameDocId').value = id;
    document.getElementById('renameName').value = currentName;
    document.getElementById('renameModal').classList.add('active');
}

async function handleRename(e) {
    e.preventDefault();
    const id = document.getElementById('renameDocId').value;
    const name = document.getElementById('renameName').value;
    const res = await fetchApi('/docs/rename', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `id=${id}&name=${encodeURIComponent(name)}`
    });
    if(res.success) {
        closeModal('renameModal');
        loadDocuments();
    }
}

async function deleteDoc(id) {
    if(confirm("Are you sure you want to delete this document AND all its versions?")) {
        const res = await fetchApi('/docs/delete', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: `id=${id}`
        });
        if(res.success) {
            loadDocuments();
            loadDashboard();
        }
    }
}

async function openHistory(id, name) {
    document.getElementById('historyModalTitle').innerText = 'History: ' + name;
    document.getElementById('historyTableBody').innerHTML = '<tr><td colspan="5">Loading...</td></tr>';
    document.getElementById('historyModal').classList.add('active');
    
    const data = await fetchApi(`/docs/history?id=${id}`);
    const tbody = document.getElementById('historyTableBody');
    tbody.innerHTML = '';
    
    if (data.success) {
        data.data.forEach(v => {
            const sizeKB = (v.file_size / 1024).toFixed(1);
            tbody.innerHTML += `
                <tr>
                    <td><span style="background:var(--border-color); padding: 2px 8px; border-radius: 20px;">v${v.version_num}</span></td>
                    <td>${sizeKB} KB</td>
                    <td>${v.notes || '-'}</td>
                    <td>${v.uploaded_at.split(' ')[0]}</td>
                    <td class="actions">
                        <button onclick="downloadVersion(${v.id})" class="primary-btn" style="margin:0; padding:4px 10px;">Download</button>
                    </td>
                </tr>
            `;
        });
    }
}

function downloadVersion(vid) {
    const token = localStorage.getItem('token');
    window.location.href = `${API_URL}/docs/download?vid=${vid}&token=${token}`;
}

// Utility
function formatBytes(bytes, decimals = 2) {
    if (!+bytes) return '0 Bytes';
    const k = 1024;
    const dm = decimals < 0 ? 0 : decimals;
    const sizes = ['Bytes', 'KB', 'MB', 'GB', 'TB', 'PB', 'EB', 'ZB', 'YB'];
    const i = Math.floor(Math.log(bytes) / Math.log(k));
    return `${parseFloat((bytes / Math.pow(k, i)).toFixed(dm))} ${sizes[i]}`;
}

// --- Teams Logic ---
let currentTeamId = null;

async function loadTeams() {
    const data = await fetchApi('/teams/list');
    const ul = document.getElementById('teamsList');
    ul.innerHTML = '';
    if (data.success) {
        if (data.data.length === 0) {
            ul.innerHTML = '<li style="padding: 1rem; color: var(--text-secondary);">No teams found.</li>';
        } else {
            data.data.forEach(t => {
                const isAdmin = t.can_manage;
                const roleBadge = isAdmin ? '<span class="badge" style="background: var(--primary); color: white;">Manager</span>' : '<span class="badge">Member</span>';
                ul.innerHTML += `
                    <li onclick="showTeamDetails(${t.id}, '${t.name.replace(/'/g, "\\'")}', ${isAdmin})" style="padding: 1rem; border-bottom: 1px solid var(--border-color); cursor: pointer; display: flex; justify-content: space-between; align-items: center; transition: background 0.2s;" onmouseover="this.style.background='var(--hover-bg)'" onmouseout="this.style.background='transparent'">
                        <div>
                            <div style="font-weight: 500; margin-bottom: 0.25rem;">${t.name}</div>
                            <div style="font-size: 0.85rem; color: var(--text-secondary);">${t.member_count} members</div>
                        </div>
                        <div>${roleBadge}</div>
                    </li>
                `;
            });
        }
    }
}

function openCreateTeamModal() {
    document.getElementById('newTeamName').value = '';
    document.getElementById('createTeamModal').classList.add('active');
}

async function createTeam(e) {
    if (e) e.preventDefault();
    const name = document.getElementById('newTeamName').value;
    const res = await fetchApi('/teams/create', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `name=${encodeURIComponent(name)}`
    });
    if (res.success) {
        closeModal('createTeamModal');
        loadTeams();
    } else {
        alert(res.message || "Failed to create team.");
    }
}

async function showTeamDetails(teamId, teamName, isManager) {
    currentTeamId = teamId;
    document.getElementById('teamDetailsPanel').style.display = 'block';
    document.getElementById('detailTeamName').innerText = teamName;
    
    document.getElementById('addMemberBtn').style.display = isManager ? 'block' : 'none';
    document.getElementById('teamMembersBody').innerHTML = '<tr><td colspan="6" style="padding-top: 1rem;">Loading...</td></tr>';
    
    const data = await fetchApi(`/teams/members?team_id=${teamId}`);
    if (data.success) {
        const tbody = document.getElementById('teamMembersBody');
        tbody.innerHTML = '';
        
        data.data.forEach(m => {
            let actions = '';
            if (isManager && m.email !== localStorage.getItem('email')) { // Should probably store email too
                actions = `<button onclick="removeMember(${teamId}, ${m.id})" style="color: var(--danger); background: transparent; border: none; font-size: 0.8rem; cursor: pointer;">Remove</button>`;
            }

            const canViewChecked = m.can_view ? 'checked' : '';
            const canUpdateChecked = m.can_update ? 'checked' : '';
            const canManageChecked = m.can_manage ? 'checked' : '';
            const disabledAttr = isManager ? '' : 'disabled';

            tbody.innerHTML += `
                <tr style="border-bottom: 1px solid var(--border-color);">
                    <td style="padding: 1rem 0;">${m.name}</td>
                    <td style="padding: 1rem 0; color: var(--text-secondary);">${m.email}</td>
                    <td style="padding: 1rem 0;"><input type="checkbox" ${canViewChecked} ${disabledAttr} onchange="changeMemberPermissions(${teamId}, ${m.id}, 'can_view', this.checked)"></td>
                    <td style="padding: 1rem 0;"><input type="checkbox" ${canUpdateChecked} ${disabledAttr} onchange="changeMemberPermissions(${teamId}, ${m.id}, 'can_update', this.checked)"></td>
                    <td style="padding: 1rem 0;"><input type="checkbox" ${canManageChecked} ${disabledAttr} onchange="changeMemberPermissions(${teamId}, ${m.id}, 'can_manage', this.checked)"></td>
                    <td style="padding: 1rem 0; text-align: right;">${actions}</td>
                </tr>
            `;
        });
    }
}

function openAddMemberModal() {
    document.getElementById('newMemberEmail').value = '';
    document.getElementById('addMemberModal').classList.add('active');
}

async function addTeamMember(e) {
    if (e) e.preventDefault();
    if (!currentTeamId) return;
    
    const email = document.getElementById('newMemberEmail').value;
    const canView = document.getElementById('permView').checked;
    const canUpdate = document.getElementById('permUpdate').checked;
    const canManage = document.getElementById('permManage').checked;
    
    const res = await fetchApi('/teams/members/add', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `team_id=${currentTeamId}&email=${encodeURIComponent(email)}&can_view=${canView}&can_update=${canUpdate}&can_manage=${canManage}`
    });
    
    if (res.success) {
        closeModal('addMemberModal');
        showTeamDetails(currentTeamId, document.getElementById('detailTeamName').innerText, true);
        loadTeams();
    } else {
        alert(res.message || "Failed to add member.");
    }
}

async function removeMember(teamId, userId) {
    if (confirm("Remove this member from the team?")) {
        const res = await fetchApi('/teams/members/remove', {
            method: 'POST',
            headers: {'Content-Type': 'application/x-www-form-urlencoded'},
            body: `team_id=${teamId}&user_id=${userId}`
        });
        if (res.success) {
            showTeamDetails(currentTeamId, document.getElementById('detailTeamName').innerText, 'admin');
            loadTeams();
        } else {
            alert(res.message || "Failed to remove member.");
        }
    }
}

async function changeMemberPermissions(teamId, userId, permName, value) {
    // We need to fetch current permissions first or at least keep them in state
    // For simplicity, we'll fetch team members to get most recent state
    const data = await fetchApi(`/teams/members?team_id=${teamId}`);
    if (!data.success) return;
    
    const member = data.data.find(m => m.id === userId);
    if (!member) return;
    
    // Update the specific permission
    member[permName] = value;
    
    const res = await fetchApi('/teams/members/permissions', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `team_id=${teamId}&user_id=${userId}&can_view=${member.can_view}&can_update=${member.can_update}&can_manage=${member.can_manage}`
    });
    
    if (!res.success) {
        alert(res.message || "Failed to change permissions.");
    }
    showTeamDetails(teamId, document.getElementById('detailTeamName').innerText, true);
}

// --- Share Logic ---
async function openShareModal(docId) {
    document.getElementById('shareDocId').value = docId;
    const select = document.getElementById('shareTeamSelect');
    select.innerHTML = '<option>Loading...</option>';
    document.getElementById('shareModal').classList.add('active');
    
    const data = await fetchApi('/teams/list');
    if (data.success) {
        select.innerHTML = '';
        if (data.data.length === 0) {
            select.innerHTML = '<option disabled>You are not in any teams</option>';
        } else {
            data.data.forEach(t => {
                select.innerHTML += `<option value="${t.id}">${t.name}</option>`;
            });
        }
    }
}

async function shareDocument(e) {
    if (e) e.preventDefault();
    const docId = document.getElementById('shareDocId').value;
    const teamId = document.getElementById('shareTeamSelect').value;
    
    const res = await fetchApi('/docs/share', {
        method: 'POST',
        headers: {'Content-Type': 'application/x-www-form-urlencoded'},
        body: `id=${docId}&team_id=${teamId}`
    });
    
    if (res.success) {
        closeModal('shareModal');
        alert("Document shared successfully!");
    } else {
        alert(res.message || "Failed to share document.");
    }
}
