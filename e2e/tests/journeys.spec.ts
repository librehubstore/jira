import AxeBuilder from '@axe-core/playwright'
import { expect, test, type Page } from '@playwright/test'

/**
 * Key user journeys of spec §5, run in order on a fresh instance:
 * first launch → main flow → blocking links → board configuration (+ accessibility check).
 */
test.describe.configure({ mode: 'serial' })

const ADMIN = { username: 'admin', password: 'admin-password-e2e' }
const ALICE = { username: 'alice', temporary: 'temporaire-123', password: 'alice-password-1' }

async function login(page: Page, username: string, password: string) {
  await page.goto('/login')
  await page.getByLabel('Identifiant').fill(username)
  await page.getByLabel('Mot de passe').fill(password)
  await page.getByRole('button', { name: 'Se connecter' }).click()
  await expect(page).not.toHaveURL(/\/login/)
}

async function logout(page: Page) {
  await page.getByRole('button', { name: 'Déconnexion' }).click()
  await expect(page).toHaveURL(/\/login/)
}

async function ticket(page: Page, key: string) {
  const res = await page.request.get(`/api/tickets/${key}`)
  expect(res.ok()).toBeTruthy()
  return res.json()
}

async function createTicket(page: Page, summary: string) {
  await expect(page.getByLabel('Filtrer les cartes')).toBeVisible() // shortcuts are live once the board is mounted
  await page.keyboard.press('c')
  const dialog = page.getByRole('dialog', { name: 'Créer un ticket' })
  await dialog.getByLabel('Résumé').fill(summary)
  await dialog.getByLabel('Résumé').press('Enter')
  await expect(dialog).toBeHidden()
  await expect(page.locator('[data-card]', { hasText: summary })).toBeVisible()
}

test('premier lancement : admin, compte, espace, membre', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByText('Premier lancement')).toBeVisible()
  await page.getByLabel('Identifiant').fill(ADMIN.username)
  await page.getByLabel('Nom affiché').fill('Admin Fluxo')
  await page.getByLabel('Mot de passe').fill(ADMIN.password)
  await page.getByLabel('Confirmation').fill(ADMIN.password)
  await page.getByRole('button', { name: /Créer l.administrateur/ }).click()
  await expect(page.getByRole('heading', { name: 'Espaces' })).toBeVisible()

  await page.getByRole('link', { name: 'Administration' }).click()
  await page.getByRole('button', { name: 'Nouvel utilisateur' }).click()
  await page.getByLabel('Identifiant').fill(ALICE.username)
  await page.getByLabel('Nom affiché').fill('Alice Durand')
  await page.getByLabel('Mot de passe temporaire').fill(ALICE.temporary)
  await page.getByRole('button', { name: 'Créer', exact: true }).click()
  await expect(page.getByRole('cell', { name: 'Alice Durand' })).toBeVisible()

  await page.getByRole('link', { name: 'Espaces' }).click()
  await page.getByRole('button', { name: 'Nouvel espace' }).click()
  await page.getByLabel('Nom').fill('Paiements')
  await page.getByLabel('Clé').fill('PAY')
  await page.getByRole('button', { name: /Créer l.espace/ }).click()
  await expect(page).toHaveURL(/\/spaces\/PAY$/)
  await expect(page.getByText('À faire', { exact: true })).toBeVisible()

  await page.getByRole('link', { name: 'Paramètres' }).click()
  await page.locator('select').filter({ hasText: 'Ajouter un utilisateur ou un groupe' }).selectOption({ label: 'Alice Durand' })
  await page.getByRole('button', { name: 'Ajouter' }).click()
  await expect(page.locator('li', { hasText: 'Alice Durand' })).toBeVisible()
  await logout(page)

  await login(page, ALICE.username, ALICE.temporary)
  await expect(page.getByText('Changer le mot de passe')).toBeVisible()
  await page.getByLabel('Mot de passe actuel').fill(ALICE.temporary)
  await page.getByLabel('Nouveau mot de passe').fill(ALICE.password)
  await page.getByLabel('Confirmation').fill(ALICE.password)
  await page.getByRole('button', { name: 'Enregistrer' }).click()
  await page.getByRole('link', { name: /Paiements/ }).click()
  await expect(page).toHaveURL(/\/spaces\/PAY$/)
})

test('flux principal : créer, s’assigner, avancer, mentionner, résoudre', async ({ page }) => {
  await login(page, ALICE.username, ALICE.password)
  await page.goto('/spaces/PAY')
  await createTicket(page, 'Corriger le login')

  // Open in the side panel, assign to me with "I".
  await page.locator('[data-card]', { hasText: 'Corriger le login' }).click()
  await expect(page).toHaveURL(/selected=PAY-1/)
  await expect(page.getByRole('button', { name: /M.assigner/ })).toBeVisible() // panel and assignable list loaded
  await page.keyboard.press('i')
  await expect.poll(async () => (await ticket(page, 'PAY-1')).assignee?.username).toBe(ALICE.username)
  await page.keyboard.press('Escape')

  // Keyboard alternative to drag and drop: Shift+N moves the card to the next column.
  await page.locator('[data-card="PAY-1"]').focus()
  await page.keyboard.press('Shift+N')
  await expect.poll(async () => (await ticket(page, 'PAY-1')).status.name).toBe('En cours')

  // Comment with a mention of the admin.
  await page.goto('/browse/PAY-1')
  await page.locator('#comment-composer .ProseMirror').click()
  await page.keyboard.type('Reproduit, @adm')
  await page.getByRole('option', { name: 'Admin Fluxo @admin' }).click()
  await page.keyboard.type('peux-tu valider ?')
  await page.keyboard.press('Control+Enter')
  await expect(page.locator('.markdown', { hasText: 'peux-tu valider' })).toBeVisible()
  await logout(page)

  // The admin is notified and resolves the ticket.
  await login(page, ADMIN.username, ADMIN.password)
  const bell = page.getByRole('button', { name: /Notifications, [1-9]/ })
  await expect(bell).toBeVisible()
  await bell.click()
  await page.getByRole('button', { name: /vous a mentionné dans PAY-1/ }).click()
  await expect(page).toHaveURL(/\/browse\/PAY-1/)
  await page.getByLabel('Statut').selectOption({ label: 'Terminé' })
  await expect(page.getByText('Fait', { exact: true })).toBeVisible()
})

test('collaboration : un ticket bloqué l’indique sur sa carte', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/spaces/PAY')
  await createTicket(page, 'Mettre à jour la lib OAuth')
  await createTicket(page, 'Déployer la correction')

  await page.goto('/browse/PAY-3')
  await page.getByRole('button', { name: '+ Lier un ticket' }).click()
  await page.locator('select').filter({ hasText: 'est bloqué par' }).selectOption({ label: 'est bloqué par' })
  await page.getByPlaceholder('Clé, ex. PAY-12').fill('PAY-2')
  await page.getByRole('button', { name: 'Lier', exact: true }).click()
  await expect(page.getByRole('link', { name: /PAY-2/ })).toBeVisible()

  await page.goto('/spaces/PAY')
  const blocked = page.locator('[data-card="PAY-3"] [title="Bloqué par un ticket non terminé"]')
  await expect(blocked).toBeVisible()
  for (const expected of ['En cours', 'Terminé']) {
    await page.locator('[data-card="PAY-2"]').focus()
    await page.keyboard.press('Shift+N')
    await expect.poll(async () => (await ticket(page, 'PAY-2')).status.name).toBe(expected)
  }
  await page.reload()
  await expect(blocked).toBeHidden()
})

test('configuration du board : colonne, WIP, swimlanes, filtre rapide + accessibilité', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/spaces/PAY')
  await page.getByRole('link', { name: '⚙ Configurer le board' }).click()

  page.once('dialog', (d) => d.accept('En revue'))
  await page.getByRole('button', { name: '+ Ajouter une colonne' }).click()
  const enCours = page.locator('li', { has: page.locator('input[value="En cours"]') })
  await enCours.getByLabel('WIP max').fill('1')
  await page.getByRole('button', { name: 'Enregistrer les colonnes' }).click()
  await expect(page.locator('input[value="En revue"]')).toBeVisible()
  await page.getByLabel('Swimlanes').selectOption({ label: 'Par assigné' })

  // Two tickets "En cours" with a max of 1: the column header shows the overflow.
  await page.goto('/spaces/PAY')
  const moveCard = async (key: string, shortcut: string, expected: string) => {
    await page.locator(`[data-card="${key}"]`).focus()
    await page.keyboard.press(shortcut)
    await expect.poll(async () => (await ticket(page, key)).status.name).toBe(expected)
  }
  await moveCard('PAY-3', 'Shift+N', 'En cours') // À faire → En cours
  await moveCard('PAY-1', 'Shift+P', 'En revue') // Terminé → En revue
  await moveCard('PAY-1', 'Shift+P', 'En cours')
  await page.reload()
  await expect(page.getByText('2/1')).toBeVisible()
  await expect(page.getByRole('button', { name: /Non assignés \d+ ticket/ })).toBeVisible() // swimlane header

  // Quick filter "Mes tickets" (admin has none assigned): reflected in the URL.
  await page.getByRole('button', { name: 'Mes tickets' }).click()
  await expect(page).toHaveURL(/qf=mine/)
  await expect(page.getByText('Aucun ticket ne correspond aux filtres.')).toBeVisible()

  const axe = await new AxeBuilder({ page }).withTags(['wcag2a', 'wcag2aa']).analyze()
  const blocking = axe.violations.filter((v) => v.impact === 'critical' || v.impact === 'serious')
  const describe = (n: { target: unknown[]; failureSummary?: string }) => `${n.target.join(' ')} (${n.failureSummary?.split('\n')[1]?.trim()})`
  expect(blocking.map((v) => `${v.id}: ${v.nodes.map(describe).join(' | ')}`)).toEqual([])
})

test('V1 : backlog Kanban, glisser vers « Sélectionné », recherche FQL', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/spaces/PAY')
  await page.getByRole('link', { name: '⚙ Configurer le board' }).click()
  await page.getByLabel(/Backlog Kanban/).click() // controlled: checked once the server answers
  await expect(page.getByLabel(/Backlog Kanban/)).toBeChecked()

  await page.goto('/spaces/PAY')
  await page.getByRole('link', { name: 'Backlog', exact: true }).click()
  await page.getByLabel('Résumé du nouveau ticket').fill('Préparer la démo')
  await page.getByLabel('Résumé du nouveau ticket').press('Enter')
  const row = page.locator('li', { hasText: 'Préparer la démo' })
  await expect(row).toBeVisible()
  const key = (await row.getByRole('button', { name: /^PAY-\d+$/ }).textContent())!

  // Drag by the handle into the second section (« En cours », the board's second column).
  const handle = row.getByRole('button', { name: `Déplacer ${key}` })
  const target = page.getByRole('region', { name: 'En cours' }).locator('ul')
  const from = (await handle.boundingBox())!
  const to = (await target.boundingBox())!
  await page.mouse.move(from.x + from.width / 2, from.y + from.height / 2)
  await page.mouse.down()
  await page.mouse.move(to.x + 40, to.y + to.height - 5, { steps: 15 })
  await page.mouse.up()
  await expect.poll(async () => (await ticket(page, key)).status.name).toBe('En cours')

  // FQL search with a positioned error, then a valid query.
  await page.goto('/search?mode=fql')
  const fql = page.getByLabel('Requête FQL')
  await fql.fill('summary ~ ')
  await fql.press('Escape')
  await page.getByRole('button', { name: 'Rechercher' }).click()
  await expect(page.getByRole('alert')).toContainText('position')
  await fql.fill(`key = ${key} AND status WAS "À faire"`)
  await page.getByRole('button', { name: 'Rechercher' }).click()
  await expect(page.getByRole('link', { name: key })).toBeVisible()
})

test('V1 : workflow, commentaire obligatoire à la transition', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/spaces/PAY/workflow')
  await page.getByRole('button', { name: 'Définir des transitions' }).click()
  const done = page.locator('fieldset', { has: page.locator('legend', { hasText: /^Terminé$/ }) })
  await done.getByLabel('Ajouter une règle').nth(1).selectOption('COMMENT_REQUIRED') // validators
  const saved = page.waitForResponse((r) => r.url().endsWith('/transitions') && r.request().method() === 'PUT')
  await page.getByRole('button', { name: 'Enregistrer les transitions' }).click()
  expect((await saved).ok()).toBeTruthy()

  await page.goto('/spaces/PAY')
  await page.locator('[data-card="PAY-3"]').focus()
  await page.keyboard.press('Shift+N')
  await expect.poll(async () => (await ticket(page, 'PAY-3')).status.name).toBe('En revue')

  // The transition screen opens; cancelling leaves the ticket where it was.
  await page.locator('[data-card="PAY-3"]').focus()
  await page.keyboard.press('Shift+N')
  const screen = page.getByRole('dialog', { name: 'Terminé' })
  await expect(screen).toBeVisible()
  await screen.getByRole('button', { name: 'Annuler' }).click()
  await expect(screen).toBeHidden()
  expect((await ticket(page, 'PAY-3')).status.name).toBe('En revue')

  await page.locator('[data-card="PAY-3"]').focus()
  await page.keyboard.press('Shift+N')
  await screen.getByLabel(/Commentaire/).fill('Livré en recette')
  await screen.getByRole('button', { name: 'Terminé' }).click()
  await expect.poll(async () => (await ticket(page, 'PAY-3')).status.name).toBe('Terminé')
})

test('V1 : modification en masse en tâche de fond, clonage', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/search?spaces=PAY')
  await page.getByLabel('Sélectionner PAY-1').check()
  await page.getByLabel('Sélectionner PAY-3').check()
  await page.getByRole('button', { name: 'Actions en masse…' }).click()
  const dialog = page.getByRole('dialog', { name: 'Actions en masse' })
  await dialog.getByLabel('Valeurs (séparées par des espaces)').fill('lot-e2e')
  await dialog.getByRole('button', { name: 'Lancer' }).click()
  await expect(dialog.getByRole('status')).toHaveText('2 réussi(s), 0 refusé(s)')
  await dialog.getByRole('button', { name: 'Fermer' }).click()
  expect((await ticket(page, 'PAY-3')).labels).toContain('lot-e2e')

  await page.goto('/browse/PAY-1')
  await page.getByRole('button', { name: 'Cloner', exact: true }).click()
  await page.getByRole('dialog', { name: 'Cloner PAY-1' }).getByRole('button', { name: 'Cloner' }).click()
  await expect(page).not.toHaveURL(/\/browse\/PAY-1$/)
  await expect(page.getByText('COPIE – Corriger le login').first()).toBeVisible()
})

test('V1 : champ personnalisé et composant', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/admin/fields')
  await page.getByLabel('Nouveau champ').fill('Client final')
  await page.getByRole('button', { name: 'Créer le champ' }).click()
  await expect(page.getByLabel('Nom du champ')).toHaveValue('Client final')

  await page.goto('/spaces/PAY/settings')
  await page.getByRole('checkbox', { name: 'Client final', exact: true }).click()
  await expect(page.getByRole('checkbox', { name: 'Client final', exact: true })).toBeChecked()

  await page.goto('/spaces/PAY/catalog')
  await page.getByLabel('Nouveau composant').fill('Paiement')
  await page.getByLabel('Nouveau composant').press('Enter')
  await expect(page.getByLabel('Nom du composant')).toHaveValue('Paiement')

  await page.goto('/browse/PAY-1')
  await page.getByLabel('Ajouter un composant').selectOption({ label: 'Paiement' })
  await expect.poll(async () => (await ticket(page, 'PAY-1')).components.map((c: { name: string }) => c.name)).toEqual(['Paiement'])
  await expect(page.getByText('Client final', { exact: true })).toBeVisible() // the enabled custom field is on the ticket
})

test('V1 : jeton d’API et diagramme de flux cumulé', async ({ page, request }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/preferences')
  await page.getByLabel('Nom du jeton').fill('CI')
  await page.getByRole('button', { name: 'Créer le jeton' }).click()
  const secret = (await page.locator('code', { hasText: 'flx_' }).textContent())!.trim()
  const me = await request.get('/api/me', { headers: { Authorization: `Bearer ${secret}` } })
  expect(me.ok()).toBeTruthy()
  expect((await me.json()).username).toBe(ADMIN.username)

  await page.goto('/spaces/PAY')
  await page.getByRole('link', { name: 'Flux cumulé' }).click()
  await expect(page.getByRole('img', { name: 'Diagramme de flux cumulé' })).toBeVisible()
  await expect(page.locator('polygon')).not.toHaveCount(0)
})

test('Ajouts : « + » dans une cellule, menu clic droit, archivage', async ({ page }) => {
  await login(page, ADMIN.username, ADMIN.password)
  await page.goto('/spaces/PAY')
  // « + » under the « En cours » column creates the card right there.
  const doingCell = page.locator('[data-cell]').nth(1)
  await doingCell.getByRole('button', { name: 'Ajouter une carte ici' }).click()
  await doingCell.getByLabel('Résumé de la nouvelle carte').fill('Carte rapide')
  await doingCell.getByLabel('Résumé de la nouvelle carte').press('Enter')
  const card = page.locator('[data-card]', { hasText: 'Carte rapide' })
  await expect(card).toBeVisible()
  const key = (await card.getAttribute('data-card'))!
  await expect.poll(async () => (await ticket(page, key)).status.name).toBe('En cours')

  // Right click: archive takes it off the board.
  await card.click({ button: 'right' })
  const menu = page.getByRole('menu', { name: `Actions sur ${key}` })
  await expect(menu).toBeVisible()
  await menu.getByRole('menuitem', { name: 'Archiver' }).click()
  await expect(card).toBeHidden()
  expect((await ticket(page, key)).archived).toBe(true)
})
