package mmt.standin.net.minecraft.client.multiplayer;

/**
 * 1.21's SessionSearchTrees on 1.20.6, for a port back: the class itself, which 1.20.6 does not have yet, over the search
 * trees 1.20.6's game keeps in Minecraft's SearchRegistry.
 *
 * Read from both releases: 1.21's session builds three trees from the lists it is given: creative items by tooltip text
 * and item id (updateCreativeTooltips), creative items by tag (updateCreativeTags) and recipe collections by result
 * (updateRecipes), and hands each back (creativeNameSearch, creativeTagSearch, recipes()). 1.20.6's Minecraft builds the
 * same three, with the same FullTextSearchTree and IdSearchTree over the same text, under SearchRegistry.CREATIVE_NAMES,
 * CREATIVE_TAGS and RECIPE_COLLECTIONS, filled by populateSearchTree and read by getSearchTree; and its registry rebuilds
 * them itself when the language reloads, which 1.21's rebuildAfterLanguageChange does by hand.
 *
 * Agrees with NeoForge's 1.20.6 -> 1.21 primer (https://github.com/neoforged/.github/blob/main/primers/1.21/index.md, CC BY 4.0):
 * "`net.minecraft.client.multiplayer.SessionSearchTrees` - The data contained for creative tabs and recipe collections on the client".
 */
public class SessionSearchTrees {

    final net.minecraft.client.Minecraft minecraft;

    public SessionSearchTrees(net.minecraft.client.Minecraft minecraft) {
        this.minecraft = minecraft;
    }

    public void rebuildAfterLanguageChange() {
    }

    public void updateRecipes(net.minecraft.client.ClientRecipeBook book, net.minecraft.core.RegistryAccess.Frozen registries) {
        minecraft.populateSearchTree(net.minecraft.client.searchtree.SearchRegistry.RECIPE_COLLECTIONS, book.getCollections());
    }

    public net.minecraft.client.searchtree.SearchTree recipes() {
        return minecraft.getSearchTree(net.minecraft.client.searchtree.SearchRegistry.RECIPE_COLLECTIONS);
    }

    public void updateCreativeTags(java.util.List items) {
        minecraft.populateSearchTree(net.minecraft.client.searchtree.SearchRegistry.CREATIVE_TAGS, items);
    }

    public net.minecraft.client.searchtree.SearchTree creativeTagSearch() {
        return minecraft.getSearchTree(net.minecraft.client.searchtree.SearchRegistry.CREATIVE_TAGS);
    }

    public void updateCreativeTooltips(net.minecraft.core.HolderLookup.Provider registries, java.util.List items) {
        minecraft.populateSearchTree(net.minecraft.client.searchtree.SearchRegistry.CREATIVE_NAMES, items);
    }

    public net.minecraft.client.searchtree.SearchTree creativeNameSearch() {
        return minecraft.getSearchTree(net.minecraft.client.searchtree.SearchRegistry.CREATIVE_NAMES);
    }
}
